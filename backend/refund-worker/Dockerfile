FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/refund-worker-0.1.0.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
