FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/eureka-server-0.1.0.jar app.jar
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]
