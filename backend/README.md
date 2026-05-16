# DOCGEN Java Backend

This backend uses Java's built-in `HttpServer` to serve the DOCGEN HTML pages and API routes.

## Run

From `DOCGEN\\backend`:

```bat
run.bat
```

Or manually:

```bat
javac -d out src\DocgenServer.java
java -cp out DocgenServer
```

The server now keeps its runtime data in `backend\users.json` and `backend\sessions.json` no matter whether you launch it from the repo root or from `backend`.

## Routes

- `/`
- `/login`
- `/profile`
- `/letters`
- `/forms`
- `/invitation-cards`
- `/cvs`
- `/resumes`
- `/email`
