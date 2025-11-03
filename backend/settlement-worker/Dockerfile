FROM gradle:8.7-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle.kts /workspace/
COPY backend /workspace/backend
RUN gradle :backend:settlement-worker:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/backend/settlement-worker/build/libs/settlement-worker-0.1.0.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
