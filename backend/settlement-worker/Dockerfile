FROM eclipse-temurin:21-jre
WORKDIR /app
COPY backend/settlement-worker/build/libs/settlement-worker-0.1.0.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
