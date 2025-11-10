FROM eclipse-temurin:21-jre

RUN useradd --system --create-home spring \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app \
    && chown spring:spring /app

WORKDIR /app
ARG JAR_FILE=build/libs/*.jar
COPY --chown=spring:spring ${JAR_FILE} app.jar
EXPOSE 8082
USER spring
ENTRYPOINT ["java", "-jar", "app.jar"]
