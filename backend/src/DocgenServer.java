import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DocgenServer {
    private static final String SESSION_COOKIE_NAME = "DOCGEN_SESSION";
    private static final long SESSION_DURATION_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final Path TEMPLATE_ROOT = resolveTemplateRoot();
    private static final Path DATA_ROOT = resolveDataRoot();
    private static final Path USERS_FILE = DATA_ROOT.resolve("users.json");
    private static final Path SESSIONS_FILE = DATA_ROOT.resolve("sessions.json");
    private static final Map<String, String> ROUTES = createRoutes();
    private static final Map<String, User> USERS = new ConcurrentHashMap<>();
    private static final Map<String, SessionData> SESSIONS = new ConcurrentHashMap<>();

    static class SessionData {
        String username;
        long expiresAt;

        SessionData(String username) {
            this.username = username;
            this.expiresAt = System.currentTimeMillis() + SESSION_DURATION_MS;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    static class User {
        String username;
        String email;
        String passwordHash;
        String fullName = "";
        String bio = "";
        List<DocumentHistory> documentHistory = Collections.synchronizedList(new ArrayList<>());

        User(String username, String email, String password) {
            this.username = username;
            this.email = email;
            this.passwordHash = hashPassword(password);
        }

        boolean checkPassword(String password) {
            return passwordHash.equals(hashPassword(password));
        }

        static String hashPassword(String password) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder(hash.length * 2);
                for (byte b : hash) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (Exception e) {
                throw new IllegalStateException("Could not hash password", e);
            }
        }
    }

    static class DocumentHistory {
        String id;
        String type;
        String title;
        long createdAt;
        String content;

        DocumentHistory(String type, String title, String content) {
            this(UUID.randomUUID().toString(), type, title, System.currentTimeMillis(), content);
        }

        DocumentHistory(String id, String type, String title, long createdAt, String content) {
            this.id = id;
            this.type = type;
            this.title = title;
            this.createdAt = createdAt;
            this.content = content;
        }
    }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(DATA_ROOT);
        loadUsersFromJson();
        loadSessionsFromJson();
        cleanupExpiredSessions();

        int port = Integer.parseInt(
    System.getenv().getOrDefault("PORT", "8080")
);
        HttpServer server;

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (BindException e) {
            System.err.println("Port " + port + " is busy. Looking for a free port...");
            port = findAvailablePort(port + 1, 8090);
            server = HttpServer.create(new InetSocketAddress(port), 0);
            System.out.println("Using http://localhost:" + port + " instead.");
        }

        server.createContext("/", new DocgenHandler());
        server.createContext("/api/auth/login", new AuthHandler("login"));
        server.createContext("/api/auth/register", new AuthHandler("register"));
        server.createContext("/api/auth/logout", new AuthHandler("logout"));
        server.createContext("/api/auth/check", new AuthCheckHandler());
        server.createContext("/api/profile", new ProfileHandler());
        server.createContext("/api/documents", new DocumentHandler());
        server.setExecutor(null);

        System.out.println("Using template root: " + TEMPLATE_ROOT);
        System.out.println("Using data root: " + DATA_ROOT);
        System.out.println("DOCGEN Java server running on http://localhost:" + port);
        server.start();
        Thread sessionWriter = new Thread(() -> {                                                                                   
              
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(60000);
                    cleanupExpiredSessions();
                    saveSessionsToJson();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "docgen-session-writer");
        sessionWriter.setDaemon(true);
        sessionWriter.start();
    }

    private static Map<String, String> createRoutes() {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("/", "index.html");
        routes.put("/login", "login.html");
        routes.put("/profile", "profile.html");
        routes.put("/letters", "pages/letter.html");
        routes.put("/forms", "pages/forms.html");
        routes.put("/invitation-cards", "pages/invitation-cards.html");
        routes.put("/cvs", "pages/CVs.html");
        routes.put("/resumes", "pages/resumes.html");
        routes.put("/email", "pages/email.html");
        return routes;
    }

    private static Path resolveTemplateRoot() {
        Path[] candidates = new Path[] {
           Paths.get("templates"),
            Paths.get("..", "templates"),
            Paths.get("DOCGEN", "templates"),
            Paths.get("..", "DOCGEN", "templates")
        };

        for (Path candidate : candidates) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (Files.isDirectory(absolute)) {
                return absolute;
            }
        }

        throw new IllegalStateException("Could not locate DOCGEN templates directory");
    }

    private static Path resolveDataRoot() {
        Path[] candidates = new Path[] {
            Paths.get(System.getProperty("user.dir"), "data"),
            Paths.get(".")
        };

        for (Path candidate : candidates) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (Files.isDirectory(absolute)) {
                return absolute;
            }
        }

        throw new IllegalStateException("Could not locate DOCGEN data directory");
    }

    private static void loadUsersFromJson() {
        USERS.clear();
        if (!Files.exists(USERS_FILE)) {
            System.out.println("No users.json found. Starting with empty user database.");
            return;
        }

        try {
            Map<String, String> userEntries = parseJsonObject(readUtf8File(USERS_FILE));
            for (Map.Entry<String, String> entry : userEntries.entrySet()) {
                User user = parseUser(entry.getKey(), entry.getValue());
                if (user != null) {
                    USERS.put(entry.getKey(), user);
                }
            }
            System.out.println("Loaded " + USERS.size() + " users from " + USERS_FILE.getFileName());
        } catch (Exception e) {
            System.err.println("Error loading users: " + e.getMessage());
        }
    }

    private static User parseUser(String username, String rawUserJson) {
        Map<String, String> fields = parseJsonObject(rawUserJson);
        String email = getStringField(fields, "email");
        String passwordHash = getStringField(fields, "passwordHash");
        if (email == null || passwordHash == null) {
            return null;
        }

        User user = new User(username, email, "");
        user.passwordHash = passwordHash;
        user.fullName = getStringField(fields, "fullName", "");
        user.bio = getStringField(fields, "bio", "");
        user.documentHistory = Collections.synchronizedList(parseDocumentHistory(fields.get("documentHistory")));
        return user;
    }
    private static List<DocumentHistory> parseDocumentHistory(String rawHistory) {
        List<DocumentHistory> history = new ArrayList<>();
        if (rawHistory == null) {
            return history;
        }

        for (String item : parseJsonArray(rawHistory)) {
            Map<String, String> fields = parseJsonObject(item);
            String id = getStringField(fields, "id");
            String type = getStringField(fields, "type");
            String title = getStringField(fields, "title");
            String content = getStringField(fields, "content", "");
            Long createdAt = getLongField(fields, "createdAt");
            if (id != null && type != null && title != null && createdAt != null) {
                history.add(new DocumentHistory(id, type, title, createdAt.longValue(), content));
            }
        }
        return history;
    }

    private static void saveUsersToJson() {
        try {
            Files.createDirectories(DATA_ROOT);
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, User> entry : USERS.entrySet()) {
                if (!first) {
                    json.append(",");
                }
                first = false;
                json.append(toJsonString(entry.getKey())).append(":").append(toUserJson(entry.getValue()));
            }
            json.append("}");
            writeUtf8File(USERS_FILE, json.toString());
        } catch (Exception e) {
            System.err.println("Error saving users: " + e.getMessage());
        }
    }

    private static String toUserJson(User user) {
        StringBuilder history = new StringBuilder("[");
        boolean first = true;
        synchronized (user.documentHistory) {
            for (DocumentHistory document : user.documentHistory) {
                if (!first) {
                    history.append(",");
                }
                first = false;
                history.append(toDocumentJson(document));
            }
        }
        history.append("]");

        return "{"
            + "\"email\":" + toJsonString(user.email) + ","
            + "\"passwordHash\":" + toJsonString(user.passwordHash) + ","
            + "\"fullName\":" + toJsonString(user.fullName) + ","
            + "\"bio\":" + toJsonString(user.bio) + ","
            + "\"documentHistory\":" + history
            + "}";
    }

    private static String toDocumentJson(DocumentHistory document) {
        return "{"
            + "\"id\":" + toJsonString(document.id) + ","
            + "\"type\":" + toJsonString(document.type) + ","
            + "\"title\":" + toJsonString(document.title) + ","
            + "\"createdAt\":" + document.createdAt + ","
            + "\"content\":" + toJsonString(document.content)
            + "}";
    }

    private static void loadSessionsFromJson() {
        SESSIONS.clear();
        if (!Files.exists(SESSIONS_FILE)) {
            return;
        }

        try {
            Map<String, String> sessionEntries = parseJsonObject(readUtf8File(SESSIONS_FILE));
            for (Map.Entry<String, String> entry : sessionEntries.entrySet()) {
                Map<String, String> fields = parseJsonObject(entry.getValue());
                String username = getStringField(fields, "username");
                Long expiresAt = getLongField(fields, "expiresAt");
                if (username != null && expiresAt != null && expiresAt > System.currentTimeMillis()) {
                    SessionData session = new SessionData(username);
                    session.expiresAt = expiresAt;
                    SESSIONS.put(entry.getKey(), session);
                }
            }
            System.out.println("Loaded " + SESSIONS.size() + " sessions from " + SESSIONS_FILE.getFileName());
        } catch (Exception e) {
            System.err.println("Error loading sessions: " + e.getMessage());
        }
    }

    private static void saveSessionsToJson() {
        try {
            Files.createDirectories(DATA_ROOT);
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, SessionData> entry : SESSIONS.entrySet()) {
                if (entry.getValue().isExpired()) {
                    continue;
                }
                if (!first) {
                    json.append(",");
                }
                first = false;
                json.append(toJsonString(entry.getKey()))
                    .append(":{\"username\":")
                    .append(toJsonString(entry.getValue().username))
                    .append(",\"expiresAt\":")
                    .append(entry.getValue().expiresAt)
                    .append("}");
            }
            json.append("}");
            writeUtf8File(SESSIONS_FILE, json.toString());
        } catch (Exception e) {
            System.err.println("Error saving sessions: " + e.getMessage());
        }
    }

    private static void cleanupExpiredSessions() {
        SESSIONS.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private static int findAvailablePort(int start, int end) throws IOException {
        for (int port = start; port <= end; port++) {
            try (ServerSocket socket = new ServerSocket(port)) {
                socket.setReuseAddress(true);
                return port;
            } catch (IOException ignored) {
                // Try the next port.
            }
        }
        throw new IOException("No available port found between " + start + " and " + end);
    }

    private static void applyCors(HttpExchange exchange, String methods) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String allowOrigin = (origin == null || origin.trim().isEmpty()) ? "*" : origin;
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", allowOrigin);
        if (!"*".equals(allowOrigin)) {
            exchange.getResponseHeaders().set("Vary", "Origin");
            exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", methods);
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static boolean handleOptions(HttpExchange exchange, String methods) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            applyCors(exchange, methods);
            exchange.sendResponseHeaders(200, -1);
            return true;
        }
        return false;
    }

    private static SessionData getValidSession(HttpExchange exchange) {
        String sessionId = getSessionId(exchange);
        if (sessionId == null) {
            return null;
        }

        SessionData session = SESSIONS.get(sessionId);
        if (session == null) {
            return null;
        }
        if (session.isExpired()) {
            SESSIONS.remove(sessionId);
            saveSessionsToJson();
            return null;
        }
        return session;
    }

    private static User requireAuthenticatedUser(HttpExchange exchange) throws IOException {
        SessionData session = getValidSession(exchange);
        if (session == null) {
            sendJsonResponse(exchange, 401, "{\"error\":\"Unauthorized\"}");
            return null;
        }

        User user = USERS.get(session.username);
        if (user == null) {
            sendJsonResponse(exchange, 401, "{\"error\":\"User not found\"}");
            return null;
        }
        return user;
    }

    private static String getSessionId(HttpExchange exchange) {
        String cookies = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookies == null) {
            return null;
        }

        for (String cookie : cookies.split(";")) {
            String trimmed = cookie.trim();
            String prefix = SESSION_COOKIE_NAME + "=";
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length());
            }
        }
        return null;
    }

    private static Map<String, String> parseRequestJson(HttpExchange exchange) throws IOException {
        String body = new String(readAllBytes(exchange.getRequestBody()), StandardCharsets.UTF_8);
        return parseJsonObject(body);
    }
    private static Map<String, String> parseJsonObject(String json) {
        String trimmed = json == null ? "" : json.trim();
        Map<String, String> result = new LinkedHashMap<>();
        if (trimmed.isEmpty()) {
            return result;
        }
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new IllegalArgumentException("Expected a JSON object");
        }

        String content = trimmed.substring(1, trimmed.length() - 1).trim();
        if (content.isEmpty()) {
            return result;
        }

        for (String entry : splitTopLevel(content)) {
            int separator = findTopLevelSeparator(entry, ':');
            if (separator <= 0) {
                continue;
            }
            String rawKey = entry.substring(0, separator).trim();
            String rawValue = entry.substring(separator + 1).trim();
            result.put(parseJsonString(rawKey), rawValue);
        }
        return result;
    }

    private static List<String> parseJsonArray(String json) {
        String trimmed = json == null ? "" : json.trim();
        List<String> values = new ArrayList<>();
        if (trimmed.isEmpty() || "null".equals(trimmed)) {
            return values;
        }
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            throw new IllegalArgumentException("Expected a JSON array");
        }

        String content = trimmed.substring(1, trimmed.length() - 1).trim();
        if (content.isEmpty()) {
            return values;
        }

        values.addAll(splitTopLevel(content));
        return values;
    }

    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        boolean escaping = false;
        int depth = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaping) {
                current.append(c);
                escaping = false;
                continue;
            }
            if (c == '\\') {
                current.append(c);
                escaping = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                current.append(c);
                continue;
            }
            if (!inString) {
                if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    parts.add(current.toString().trim());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(c);
        }

        if (current.length() > 0) {
            parts.add(current.toString().trim());
        }
        return parts;
    }

    private static int findTopLevelSeparator(String text, char separator) {
        boolean inString = false;
        boolean escaping = false;
        int depth = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaping) {
                escaping = false;
                continue;
            }
            if (c == '\\') {
                escaping = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                } else if (c == separator && depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String getStringField(Map<String, String> fields, String key) {
        return getStringField(fields, key, null);
    }

    private static String getStringField(Map<String, String> fields, String key, String defaultValue) {
        String rawValue = fields.get(key);
        if (rawValue == null || "null".equals(rawValue.trim())) {
            return defaultValue;
        }
        return parseJsonString(rawValue);
    }

    private static Long getLongField(Map<String, String> fields, String key) {
        String rawValue = fields.get(key);
        if (rawValue == null) {
            return null;
        }
        try {
            return Long.valueOf(rawValue.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String parseJsonString(String rawValue) {
        String trimmed = rawValue == null ? "" : rawValue.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '"' || trimmed.charAt(trimmed.length() - 1) != '"') {
            throw new IllegalArgumentException("Expected a JSON string");
        }

        StringBuilder output = new StringBuilder(trimmed.length() - 2);
        boolean escaping = false;
        for (int i = 1; i < trimmed.length() - 1; i++) {
            char c = trimmed.charAt(i);
            if (escaping) {
                switch (c) {
                    case '"':
                    case '\\':
                    case '/':
                        output.append(c);
                        break;
                    case 'b':
                        output.append('\b');
                        break;
                    case 'f':
                        output.append('\f');
                        break;
                    case 'n':
                        output.append('\n');
                        break;
                    case 'r':
                        output.append('\r');
                        break;
                    case 't':
                        output.append('\t');
                        break;
                    case 'u':
                        if (i + 4 >= trimmed.length() - 1) {
                            throw new IllegalArgumentException("Invalid unicode escape");
                        }
                        output.append((char) Integer.parseInt(trimmed.substring(i + 1, i + 5), 16));
                        i += 4;
                        break;
                    default:
                        output.append(c);
                }
                escaping = false;
                continue;
            }
            if (c == '\\') {
                escaping = true;
            } else {
                output.append(c);
            }
        }
        return output.toString();
    }

    private static String toJsonString(String value) {
        return "\"" + escapeJsonString(value == null ? "" : value) + "\"";
    }

    private static String escapeJsonString(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.trim().isEmpty()) {
            return params;
        }

        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            try {
                String key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8.name());
                String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8.name());
                params.put(key, value);
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException("UTF-8 decoding is not supported", e);
            }
        }
        return params;
    }

    private static String readUtf8File(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeUtf8File(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        byte[] buffer = new byte[4096];
        int read;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void serveTemplateFile(HttpExchange exchange, String relativePath, String contentType) throws IOException {
        Path target = TEMPLATE_ROOT.resolve(relativePath).normalize();
        if (!target.startsWith(TEMPLATE_ROOT) || !Files.exists(target)) {
            sendResponse(exchange, 404, "text/plain; charset=UTF-8", "404 - File not found");
            return;
        }
        sendResponse(exchange, 200, contentType, Files.readAllBytes(target));
    }

    private static String getMimeType(String filename) {
        if (filename.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }
        if (filename.endsWith(".js")) {
            return "application/javascript; charset=UTF-8";
        }
        if (filename.endsWith(".png")) {
            return "image/png";
        }
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (filename.endsWith(".gif")) {
            return "image/gif";
        }
        if (filename.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (filename.endsWith(".ico")) {
            return "image/x-icon";
        }
        return "text/html; charset=UTF-8";
    }

    private static void sendResponse(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        sendResponse(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendResponse(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void sendJsonResponse(HttpExchange exchange, int status, String json) throws IOException {
        sendResponse(exchange, status, "application/json; charset=UTF-8", json);
    }
    private static class AuthCheckHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            applyCors(exchange, "GET, OPTIONS");
            if (handleOptions(exchange, "GET, OPTIONS")) {
                return;
            }

            SessionData session = getValidSession(exchange);
            if (session == null) {
                sendJsonResponse(exchange, 200, "{\"authenticated\":false}");
                return;
            }

            String response = "{\"authenticated\":true,\"username\":" + toJsonString(session.username) + "}";
            sendJsonResponse(exchange, 200, response);
        }
    }

    private static class AuthHandler implements HttpHandler {
        private final String action;

        AuthHandler(String action) {
            this.action = action;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            applyCors(exchange, "POST, OPTIONS");
            if (handleOptions(exchange, "POST, OPTIONS")) {
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            switch (action) {
                case "login":
                    handleLogin(exchange);
                    break;
                case "register":
                    handleRegister(exchange);
                    break;
                case "logout":
                    handleLogout(exchange);
                    break;
                default:
                    sendJsonResponse(exchange, 404, "{\"error\":\"Unknown auth action\"}");
            }
        }

        private void handleLogin(HttpExchange exchange) throws IOException {
            try {
                Map<String, String> params = parseRequestJson(exchange);
                String username = getStringField(params, "username");
                String password = getStringField(params, "password");
                if (username == null || password == null || username.trim().isEmpty() || password.isEmpty()) {
                    sendJsonResponse(exchange, 400, "{\"error\":\"Missing username or password\"}");
                    return;
                }

                User user = USERS.get(username);
                if (user != null) {
                    if (!user.checkPassword(password)) {
                        sendJsonResponse(exchange, 401, "{\"error\":\"Invalid username or password\"}");
                        return;
                    }
                } else {
                    user = new User(username, username + "@docgen.local", password);
                    USERS.put(username, user);
                    saveUsersToJson();
                }

                String sessionId = UUID.randomUUID().toString();
                SESSIONS.put(sessionId, new SessionData(username));
                saveSessionsToJson();
                exchange.getResponseHeaders().add(
                    "Set-Cookie",
                    SESSION_COOKIE_NAME + "=" + sessionId + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000"
                );
                sendJsonResponse(
                    exchange,
                    200,
                    "{\"success\":true,\"sessionId\":" + toJsonString(sessionId) + ",\"username\":" + toJsonString(username) + "}"
                );
            } catch (IllegalArgumentException e) {
                sendJsonResponse(exchange, 400, "{\"error\":\"Invalid request format\"}");
            }
        }

        private void handleRegister(HttpExchange exchange) throws IOException {
            try {
                Map<String, String> params = parseRequestJson(exchange);
                String username = getStringField(params, "username");
                String email = getStringField(params, "email");
                String password = getStringField(params, "password");
                if (username == null || email == null || password == null) {
                    sendJsonResponse(exchange, 400, "{\"error\":\"Missing required fields\"}");
                    return;
                }
                if (USERS.containsKey(username)) {
                    sendJsonResponse(exchange, 400, "{\"error\":\"Username already exists\"}");
                    return;
                }

                USERS.put(username, new User(username, email, password));
                saveUsersToJson();
                sendJsonResponse(exchange, 201, "{\"success\":true,\"message\":\"Account created successfully\"}");
            } catch (IllegalArgumentException e) {
                sendJsonResponse(exchange, 400, "{\"error\":\"Invalid request format\"}");
            }
        }

        private void handleLogout(HttpExchange exchange) throws IOException {
            String sessionId = getSessionId(exchange);
            if (sessionId != null) {
                SESSIONS.remove(sessionId);
            }
            saveSessionsToJson();
            exchange.getResponseHeaders().add(
                "Set-Cookie",
                SESSION_COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"
            );
            sendJsonResponse(exchange, 200, "{\"success\":true}");
        }
    }

    private static class DocgenHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String route = exchange.getRequestURI().getPath();

            if (isStaticAsset(route)) {
                serveTemplateFile(exchange, route.substring(1), getMimeType(route));
                return;
            }

            String target = ROUTES.get(route);
            if (target == null) {
                sendResponse(exchange, 404, "text/plain; charset=UTF-8", "404 - Page not found");
                return;
            }

            if ("/".equals(route) || "/login".equals(route)) {
                serveTemplateFile(exchange, target, "text/html; charset=UTF-8");
                return;
            }

            if (getValidSession(exchange) == null) {
                exchange.getResponseHeaders().add("Location", "/login");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }

            serveTemplateFile(exchange, target, "text/html; charset=UTF-8");
        }

        private boolean isStaticAsset(String route) {
            return route.endsWith(".css")
                || route.endsWith(".js")
                || route.endsWith(".png")
                || route.endsWith(".jpg")
                || route.endsWith(".jpeg")
                || route.endsWith(".gif")
                || route.endsWith(".svg")
                || route.endsWith(".ico");
        }
    }

    private static class ProfileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            applyCors(exchange, "GET, PUT, OPTIONS");
            if (handleOptions(exchange, "GET, PUT, OPTIONS")) {
                return;
            }

            User user = requireAuthenticatedUser(exchange);
            if (user == null) {
                return;
            }

            if ("GET".equals(exchange.getRequestMethod())) {
                sendJsonResponse(
                    exchange,
                    200,
                    "{\"username\":" + toJsonString(user.username)
                        + ",\"email\":" + toJsonString(user.email)
                        + ",\"fullName\":" + toJsonString(user.fullName)
                        + ",\"bio\":" + toJsonString(user.bio)
                        + "}"
                );
                return;
            }

            if (!"PUT".equals(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            try {
                Map<String, String> params = parseRequestJson(exchange);
                String email = getStringField(params, "email", user.email);
                String fullName = getStringField(params, "fullName", user.fullName);
                String bio = getStringField(params, "bio", user.bio);
                String currentPassword = getStringField(params, "currentPassword");
                String newPassword = getStringField(params, "newPassword");

                if (newPassword != null && !newPassword.isEmpty()) {
                    if (currentPassword == null || !user.checkPassword(currentPassword)) {
                        sendJsonResponse(exchange, 400, "{\"error\":\"Current password is incorrect\"}");
                        return;
                    }
                    user.passwordHash = User.hashPassword(newPassword);
                }

                user.email = email;
                user.fullName = fullName;
                user.bio = bio;
                saveUsersToJson();
                sendJsonResponse(exchange, 200, "{\"success\":true,\"message\":\"Profile updated successfully\"}");
            } catch (IllegalArgumentException e) {
                sendJsonResponse(exchange, 400, "{\"error\":\"Invalid request format\"}");
            }
        }
    }
    private static class DocumentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            applyCors(exchange, "GET, POST, PUT, DELETE, OPTIONS");
            if (handleOptions(exchange, "GET, POST, PUT, DELETE, OPTIONS")) {
                return;
            }

            User user = requireAuthenticatedUser(exchange);
            if (user == null) {
                return;
            }

            switch (exchange.getRequestMethod()) {
                case "GET":
                    handleGet(exchange, user);
                    break;
                case "POST":
                    handleCreate(exchange, user);
                    break;
                case "PUT":
                    handleUpdate(exchange, user);
                    break;
                case "DELETE":
                    handleDelete(exchange, user);
                    break;
                default:
                    sendJsonResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            }
        }

        private void handleGet(HttpExchange exchange, User user) throws IOException {
            StringBuilder response = new StringBuilder("[");
            boolean first = true;
            synchronized (user.documentHistory) {
                for (DocumentHistory doc : user.documentHistory) {
                    if (!first) {
                        response.append(",");
                    }
                    first = false;
                    response.append(toDocumentJson(doc));
                }
            }
            response.append("]");
            sendJsonResponse(exchange, 200, response.toString());
        }

        private void handleCreate(HttpExchange exchange, User user) throws IOException {
            try {
                Map<String, String> params = parseRequestJson(exchange);
                String type = getStringField(params, "type");
                String title = getStringField(params, "title");
                String content = getStringField(params, "content");
                if (type == null || title == null || content == null) {
                    sendJsonResponse(exchange, 400, "{\"error\":\"Missing required fields\"}");
                    return;
                }

                DocumentHistory doc = new DocumentHistory(type, title, content);
                synchronized (user.documentHistory) {
                    user.documentHistory.add(doc);
                }
                saveUsersToJson();
                sendJsonResponse(exchange, 201, "{\"success\":true,\"id\":" + toJsonString(doc.id) + "}");
            } catch (IllegalArgumentException e) {
                sendJsonResponse(exchange, 400, "{\"error\":\"Invalid request format\"}");
            }
        }

        private void handleUpdate(HttpExchange exchange, User user) throws IOException {
            try {
                Map<String, String> params = parseRequestJson(exchange);
                String id = getStringField(params, "id");
                String title = getStringField(params, "title");
                String content = getStringField(params, "content");
                if (id == null) {
                    sendJsonResponse(exchange, 400, "{\"error\":\"Document ID required\"}");
                    return;
                }

                synchronized (user.documentHistory) {
                    for (DocumentHistory doc : user.documentHistory) {
                        if (!doc.id.equals(id)) {
                            continue;
                        }
                        if (title != null) {
                            doc.title = title;
                        }
                        if (content != null) {
                            doc.content = content;
                        }
                        saveUsersToJson();
                        sendJsonResponse(exchange, 200, "{\"success\":true,\"message\":\"Document updated\"}");
                        return;
                    }
                }
                sendJsonResponse(exchange, 404, "{\"error\":\"Document not found\"}");
            } catch (IllegalArgumentException e) {
                sendJsonResponse(exchange, 400, "{\"error\":\"Invalid request format\"}");
            }
        }

        private void handleDelete(HttpExchange exchange, User user) throws IOException {
            String id = parseQuery(exchange.getRequestURI().getQuery()).get("id");
            if (id == null || id.isEmpty()) {
                sendJsonResponse(exchange, 400, "{\"error\":\"Document ID required\"}");
                return;
            }

            boolean removed;
            synchronized (user.documentHistory) {
                removed = user.documentHistory.removeIf(doc -> doc.id.equals(id));
            }
            if (!removed) {
                sendJsonResponse(exchange, 404, "{\"error\":\"Document not found\"}");
                return;
            }

            saveUsersToJson();
            sendJsonResponse(exchange, 200, "{\"success\":true,\"message\":\"Document deleted\"}");
        }
    }
}
