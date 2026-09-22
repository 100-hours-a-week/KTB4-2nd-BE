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

FROM eclipse-temurin:${JAVA_VERSION}-jre-jammy AS runtime
WORKDIR /app

ENV SERVER_PORT=8080

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        curl \
        imagemagick \
        libimage-exiftool-perl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 1001 spring \
    && useradd --system --uid 1001 --gid spring spring

COPY --from=build --chown=spring:spring /workspace/app.jar /app/app.jar

USER spring

EXPOSE 8080

CMD ["java", "-jar", "/app/app.jar"]
