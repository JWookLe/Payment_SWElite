# Build stage
FROM gradle:8-jdk21 AS builder
WORKDIR /build
COPY backend/monitoring-service/build.gradle.kts ./build.gradle.kts
COPY backend/monitoring-service/settings.gradle.kts ./settings.gradle.kts
COPY backend/monitoring-service/src ./src
RUN gradle build -x test --no-daemon

# Runtime stage
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
COPY --from=builder --chown=spring:spring /build/build/libs/*.jar app.jar
RUN mkdir -p ./scripts ./loadtest && chown -R spring:spring /app

# Install MCP server dependencies
COPY --chown=spring:spring mcp-servers ./mcp-servers
RUN cd /app/mcp-servers/ai-test-analyzer && npm install --production && npm run build

EXPOSE 8082
USER spring
ENTRYPOINT ["java", "-jar", "app.jar"]
