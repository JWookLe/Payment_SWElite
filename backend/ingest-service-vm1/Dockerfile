# Build stage
FROM gradle:8-jdk21 AS builder
WORKDIR /build
COPY backend/ingest-service-vm1/build.gradle.kts backend/ingest-service-vm1/settings.gradle.kts ./
COPY backend/ingest-service-vm1/src ./src
RUN gradle :backend:ingest-service-vm1:build -x test --no-daemon

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
EXPOSE 8080
USER spring
ENTRYPOINT ["java", "-jar", "app.jar"]
