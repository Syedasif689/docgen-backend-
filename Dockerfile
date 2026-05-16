FROM eclipse-temurin:17

WORKDIR /app

COPY . .

RUN mkdir -p out
RUN javac -d out DocgenServer.java

CMD ["java", "-cp", "out", "DocgenServer"]