FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/ingest-service-0.1.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
