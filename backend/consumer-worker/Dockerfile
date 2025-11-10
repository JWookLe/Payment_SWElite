FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/consumer-worker-0.1.0.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
