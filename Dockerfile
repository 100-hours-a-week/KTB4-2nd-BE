# syntax=docker/dockerfile:1.7

ARG JAVA_VERSION=25

FROM eclipse-temurin:${JAVA_VERSION}-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew

COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew clean bootJar --no-daemon \
    && artifact="$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1)" \
    && test -n "${artifact}" \
    && cp "${artifact}" /workspace/app.jar

# Rehearsal branch only: preserve the successful image's exact JRE/OS/tools.
# Build from the same deployed parent; change the application jar only.
FROM ghcr.io/100-hours-a-week/yeodam-backend@sha256:d39fd16fc92e77d9c51be9056a2d0c79e355fdf0ad81e38a8d463ab554fd2010 AS runtime
COPY --from=build --chown=spring:spring /workspace/app.jar /app/app.jar
