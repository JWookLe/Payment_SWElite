#!/bin/bash

# k6 Load Test Runner Script
# Production Pattern: Run tests from host, monitor results via API

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
K6_DIR="$PROJECT_ROOT/loadtest/k6"
RESULTS_FILE="$K6_DIR/summary.json"

# Scenario configuration
SCENARIO="${1:-authorize-only}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
DOCKER_NETWORK="payment_swelite_default"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}k6 Load Test Runner${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

# Check scenario
case "$SCENARIO" in
  authorize-only)
    echo -e "${YELLOW}Scenario:${NC} Authorization only"
    ENABLE_CAPTURE="false"
    ENABLE_REFUND="false"
    ;;
  authorize-capture)
    echo -e "${YELLOW}Scenario:${NC} Authorization + Capture"
    ENABLE_CAPTURE="true"
    ENABLE_REFUND="false"
    ;;
  full-flow)
    echo -e "${YELLOW}Scenario:${NC} Full flow (Auth + Capture + Refund)"
    ENABLE_CAPTURE="true"
    ENABLE_REFUND="true"
    ;;
  *)
    echo -e "${RED}Error:${NC} Unknown scenario '$SCENARIO'"
    echo "Available scenarios: authorize-only, authorize-capture, full-flow"
    exit 1
    ;;
esac

echo -e "${YELLOW}Base URL:${NC} $BASE_URL"
echo -e "${YELLOW}Docker Network:${NC} $DOCKER_NETWORK"
echo ""

# Check Docker network
if ! docker network inspect "$DOCKER_NETWORK" > /dev/null 2>&1; then
    echo -e "${RED}Error:${NC} Docker network '$DOCKER_NETWORK' not found"
    echo "Please start the application first: docker compose up -d"
    exit 1
fi

# Check k6 directory
if [ ! -d "$K6_DIR" ]; then
    echo -e "${RED}Error:${NC} k6 directory not found: $K6_DIR"
    exit 1
fi

# Check k6 script
if [ ! -f "$K6_DIR/payment-scenario.js" ]; then
    echo -e "${RED}Error:${NC} k6 script not found: $K6_DIR/payment-scenario.js"
    exit 1
fi

echo -e "${GREEN}Starting k6 test...${NC}"
echo ""

# Run k6 using Docker
docker run --rm \
  --network "$DOCKER_NETWORK" \
  -v "$K6_DIR:/k6" \
  -e BASE_URL="http://gateway:8083" \
  -e ENABLE_CAPTURE="$ENABLE_CAPTURE" \
  -e ENABLE_REFUND="$ENABLE_REFUND" \
  grafana/k6:0.49.0 \
  run /k6/payment-scenario.js \
  --summary-export=/k6/summary.json

# Check results
if [ -f "$RESULTS_FILE" ]; then
    echo ""
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}Test completed successfully!${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    echo -e "Results saved to: ${YELLOW}$RESULTS_FILE${NC}"
    echo ""
    echo -e "${YELLOW}Next steps:${NC}"
    echo "  - View results in Claude Desktop (MCP)"
    echo "  - curl http://localhost:8082/monitoring/loadtest/latest-result"
    echo "  - curl http://localhost:8082/monitoring/loadtest/analyze"
else
    echo -e "${RED}Warning:${NC} Results file not created"
    exit 1
fi
