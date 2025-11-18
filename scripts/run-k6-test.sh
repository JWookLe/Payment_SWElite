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
DEFAULT_BASE_URL="http://172.25.0.79:8080/api"
BASE_URL="${BASE_URL:-$DEFAULT_BASE_URL}"
DOCKER_NETWORK="payment-net"
FORCE_DOCKER_K6="${FORCE_DOCKER_K6:-false}"

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

# Check Docker network (skip if running inside container)
if [ ! -f "/.dockerenv" ]; then
    if ! docker network inspect "$DOCKER_NETWORK" > /dev/null 2>&1; then
        echo -e "${RED}Error:${NC} Docker network '$DOCKER_NETWORK' not found"
        echo "Please start the application first: docker compose up -d"
        exit 1
    fi
else
    echo -e "${YELLOW}Running inside Docker container, skipping network check${NC}"
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

# Determine whether local k6 binary is available (preferred inside containers)
USE_LOCAL_K6=false
if command -v k6 >/dev/null 2>&1 && [ "$FORCE_DOCKER_K6" != "true" ]; then
  USE_LOCAL_K6=true
fi

# Ensure previous result is gone
rm -f "$RESULTS_FILE"

if [ "$USE_LOCAL_K6" = true ]; then
  echo -e "${YELLOW}k6 binary detected locally. Running without Docker.${NC}"
  BASE_URL="$BASE_URL" \
  ENABLE_CAPTURE="$ENABLE_CAPTURE" \
  ENABLE_REFUND="$ENABLE_REFUND" \
  k6 run "$K6_DIR/payment-scenario.js" \
    --summary-export "$RESULTS_FILE"
else
  echo -e "${YELLOW}k6 binary not found. Falling back to Docker (requires Docker socket access).${NC}"
  # Resolve host path for Docker Desktop (Windows) vs Linux host
  HOST_K6_DIR="$K6_DIR"
  if command -v cygpath >/dev/null 2>&1; then
    HOST_K6_DIR="$(cygpath -aw "$K6_DIR")"
  fi

  docker run --rm \
    --network "$DOCKER_NETWORK" \
    -v "$HOST_K6_DIR:/k6" \
    -e BASE_URL="$BASE_URL" \
    -e ENABLE_CAPTURE="$ENABLE_CAPTURE" \
    -e ENABLE_REFUND="$ENABLE_REFUND" \
    grafana/k6:0.49.0 \
    run /k6/payment-scenario.js \
    --summary-export=/k6/summary.json
fi

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
