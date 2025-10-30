FROM gradle:8.5-jdk21 AS builder
WORKDIR /app
COPY settings.gradle.kts ./
COPY backend/build.gradle.kts ./backend/
COPY backend/monitoring-service/build.gradle.kts ./backend/monitoring-service/
COPY backend/monitoring-service/src ./backend/monitoring-service/src
RUN gradle :backend:monitoring-service:build -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/backend/monitoring-service/build/libs/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
