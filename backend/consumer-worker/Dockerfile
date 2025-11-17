# Build stage
FROM gradle:8-jdk21 AS builder
WORKDIR /build
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN gradle build -x test --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre

RUN useradd --system --create-home spring \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app \
    && chown spring:spring /app

WORKDIR /app
COPY --from=builder --chown=spring:spring /build/build/libs/*.jar app.jar
EXPOSE 8081
USER spring
ENTRYPOINT ["java", "-jar", "app.jar"]
