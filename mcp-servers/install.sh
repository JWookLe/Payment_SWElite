#!/bin/bash

# MCP Servers Installation Script for Payment_SWElite

set -e

echo "========================================="
echo "Payment MCP Servers Installation"
echo "========================================="
echo ""

# Check Node.js
if ! command -v node &> /dev/null; then
    echo "❌ Node.js is not installed. Please install Node.js 18+ first."
    exit 1
fi

NODE_VERSION=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
if [ "$NODE_VERSION" -lt 18 ]; then
    echo "❌ Node.js version must be 18 or higher. Current: $(node -v)"
    exit 1
fi

echo "✅ Node.js $(node -v) detected"
echo ""

# Install each MCP server
SERVERS=("circuit-breaker-mcp" "database-query-mcp" "redis-cache-mcp")

for server in "${SERVERS[@]}"; do
    echo "Installing $server..."
    cd "$server"
    npm install
    npm run build
    echo "✅ $server installed successfully"
    echo ""
    cd ..
done

echo "========================================="
echo "✅ All MCP servers installed!"
echo "========================================="
echo ""
echo "Next steps:"
echo "1. Configure Claude Desktop (see README.md)"
echo "2. Start Payment System: docker compose up -d"
echo "3. Restart Claude Desktop"
echo ""
echo "Example claude_desktop_config.json:"
echo ""
cat << 'EOF'
{
  "mcpServers": {
    "payment-circuit-breaker": {
      "command": "node",
      "args": ["<ABSOLUTE_PATH>/circuit-breaker-mcp/dist/index.js"],
      "env": {
        "API_BASE_URL": "http://localhost:8080",
        "PROMETHEUS_URL": "http://localhost:9090"
      }
    },
    "payment-database": {
      "command": "node",
      "args": ["<ABSOLUTE_PATH>/database-query-mcp/dist/index.js"],
      "env": {
        "PAYMENT_DB_HOST": "localhost",
        "PAYMENT_DB_PORT": "3306",
        "PAYMENT_DB_USER": "payuser",
        "PAYMENT_DB_PASSWORD": "paypass",
        "PAYMENT_DB_NAME": "paydb"
      }
    },
    "payment-redis": {
      "command": "node",
      "args": ["<ABSOLUTE_PATH>/redis-cache-mcp/dist/index.js"],
      "env": {
        "REDIS_URL": "redis://localhost:6379"
      }
    }
  }
}
EOF
echo ""
echo "Replace <ABSOLUTE_PATH> with: $(pwd)"
