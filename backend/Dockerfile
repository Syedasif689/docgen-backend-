FROM eclipse-temurin:17

WORKDIR /app

COPY . .
COPY ../templates ../templates

RUN mkdir -p out
RUN javac -d out DocgenServer.java

CMD ["java", "-cp", "out", "DocgenServer"]
