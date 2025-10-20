FROM gradle:8.7-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle.kts /workspace/
COPY backend /workspace/backend
RUN gradle :backend:consumer-worker:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/backend/consumer-worker/build/libs/consumer-worker-0.1.0.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
