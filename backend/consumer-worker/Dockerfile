FROM gradle:8.7-jdk21 AS build
WORKDIR /workspace
COPY backend /workspace/backend
WORKDIR /workspace/backend
RUN gradle :consumer-worker:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/backend/consumer-worker/build/libs/consumer-worker-0.1.0.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
