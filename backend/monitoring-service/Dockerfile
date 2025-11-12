FROM eclipse-temurin:21-jre

RUN useradd --system --create-home spring \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        curl \
        bash \
        ca-certificates \
        gnupg \
    && mkdir -p /etc/apt/keyrings \
    && curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" | tee /etc/apt/sources.list.d/nodesource.list \
    && curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu noble stable" | tee /etc/apt/sources.list.d/docker.list \
    && curl -fsSL https://dl.k6.io/key.gpg | gpg --dearmor -o /etc/apt/keyrings/k6.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/k6.gpg] https://dl.k6.io/deb stable main" | tee /etc/apt/sources.list.d/k6.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        nodejs \
        docker-ce-cli \
        k6 \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app \
    && chown spring:spring /app \
    && usermod -aG docker spring || true

WORKDIR /app
ARG JAR_FILE=build/libs/*.jar
COPY --chown=spring:spring ${JAR_FILE} app.jar
EXPOSE 8082
USER spring
ENTRYPOINT ["java", "-jar", "app.jar"]
