FROM openjdk:17

WORKDIR /app

COPY . .

RUN mkdir -p out
RUN javac -d out src/DocgenServer.java

CMD ["java", "-cp", "out", "DocgenServer"]