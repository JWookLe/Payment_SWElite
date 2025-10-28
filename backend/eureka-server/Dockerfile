
FROM gradle:8.7-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle.kts /workspace/
COPY backend /workspace/backend
RUN gradle :backend:eureka-server:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/backend/eureka-server/build/libs/eureka-server-0.1.0.jar app.jar
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]
