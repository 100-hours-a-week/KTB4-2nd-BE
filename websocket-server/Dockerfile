# syntax=docker/dockerfile:1.7

ARG JAVA_VERSION=25

FROM eclipse-temurin:${JAVA_VERSION}-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew ./
COPY gradle ./gradle
COPY websocket-server/build.gradle websocket-server/settings.gradle ./websocket-server/
COPY websocket-server/src ./websocket-server/src

RUN chmod +x gradlew

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew -p websocket-server bootJar --no-daemon

FROM eclipse-temurin:${JAVA_VERSION}-jre-noble AS runtime
WORKDIR /app

ENV SERVER_PORT=8081

RUN groupadd --system --gid 1001 spring \
    && useradd --system --uid 1001 --gid spring spring

COPY --from=build --chown=spring:spring \
    /workspace/websocket-server/build/libs/*.jar /app/app.jar

USER spring

EXPOSE 8081

CMD ["java", "-jar", "/app/app.jar"]