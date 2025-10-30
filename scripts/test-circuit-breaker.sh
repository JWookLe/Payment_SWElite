#!/bin/bash

# Automated circuit breaker scenario test.
# Assumes docker compose services are running on the local host.

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { printf "%b[INFO]%b %s\n" "${BLUE}" "${NC}" "$1"; }
log_success() { printf "%b[OK]%b   %s\n" "${GREEN}" "${NC}" "$1"; }
log_warn() { printf "%b[WARN]%b %s\n" "${YELLOW}" "${NC}" "$1"; }
log_error() { printf "%b[ERR]%b  %s\n" "${RED}" "${NC}" "$1"; }

API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"
GATEWAY_BASE_URL="${GATEWAY_BASE_URL:-http://localhost:8080/api}"
CIRCUIT_BREAKER_ENDPOINT="${CIRCUIT_BREAKER_ENDPOINT:-${API_BASE_URL}/circuit-breaker/kafka-publisher}"
MAX_RETRIES=5
RETRY_DELAY=2

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    log_error "Required command '$1' not found."
    exit 1
  fi
}

check_prerequisites() {
  require_command docker
  require_command timeout
}

ingest_exec() {
  docker compose exec -T ingest-service "$@"
}

ingest_curl() {
  ingest_exec curl "$@"
}

check_api_health() {
  log_info "Waiting for API health endpoint..."
  for attempt in $(seq 1 "${MAX_RETRIES}"); do
    if ingest_curl -s -f "${API_BASE_URL}/actuator/health" >/dev/null 2>&1; then
      log_success "API is responsive."
      return 0
    fi
    log_warn "API not ready yet (attempt ${attempt}/${MAX_RETRIES})."
    sleep "${RETRY_DELAY}"
  done
  log_error "API did not become healthy in time."
  return 1
}

get_circuit_breaker_state() {
  ingest_curl -s "${CIRCUIT_BREAKER_ENDPOINT}" 2>/dev/null || echo "{}"
}

print_state() {
  local label=$1
  local payload
  payload="$(get_circuit_breaker_state)"

  local state successful slow slow_rate not_permitted
  state="$(echo "${payload}" | grep -o '"state":"[^"]*"' | cut -d'"' -f4)"
  successful="$(echo "${payload}" | grep -o '"numberOfSuccessfulCalls":[0-9]*' | cut -d':' -f2)"
  slow="$(echo "${payload}" | grep -o '"numberOfSlowCalls":[0-9]*' | cut -d':' -f2)"
  not_permitted="$(echo "${payload}" | grep -o '"numberOfNotPermittedCalls":[0-9]*' | cut -d':' -f2)"
  slow_rate="$(echo "${payload}" | grep -o '"slowCallRate":"[^"]*"' | cut -d'"' -f4)"

  echo ""
  log_info "${label}"
  printf "  state: %s\n" "${state:-unknown}"
  printf "  successful calls: %s\n" "${successful:-0}"
  printf "  slow calls: %s\n" "${slow:-0}"
  printf "  not permitted calls: %s\n" "${not_permitted:-0}"
  printf "  slow call rate: %s\n" "${slow_rate:-N/A}"
}

send_payment_request() {
  local merchant_id=$1
  local timeout_seconds=${2:-15}

  timeout "${timeout_seconds}" docker compose exec -T gateway curl -sSf -X POST "${GATEWAY_BASE_URL}/payments/authorize" \
    -H "Content-Type: application/json" \
    -d "{\"merchantId\":\"${merchant_id}\",\"amount\":50000,\"currency\":\"KRW\",\"idempotencyKey\":\"${merchant_id}-$(date +%s%N)\"}" \
    >/dev/null 2>&1
}

echo ""
echo "=============================================================="
echo " Circuit Breaker automated scenario (Kafka outage simulation) "
echo "=============================================================="
echo ""

check_prerequisites
check_api_health

print_state "Step 1: initial state (Kafka up)"

log_info "Step 2: sending 5 healthy requests."
for i in $(seq 1 5); do
  if send_payment_request "NORMAL_TEST_${i}" 10; then
    log_info "  request ${i} completed."
  else
    log_error "  request ${i} failed unexpectedly."
    exit 1
  fi
done
sleep 2
print_state "After healthy traffic"

log_info "Step 3: stopping Kafka broker."
docker compose stop kafka >/dev/null 2>&1 || true
sleep 5
log_success "Kafka broker stopped."

log_info "Step 4: sending 6 requests while Kafka is down (expect slow/failure)."
for i in $(seq 1 6); do
  log_info "  slow request ${i}..."
  if send_payment_request "SLOW_TEST_${i}" 15; then
    log_warn "  request ${i} succeeded unexpectedly (no slow call?)."
  else
    log_info "  request ${i} produced slow/failure as expected."
  fi
done
sleep 5
print_state "After Kafka downtime traffic"

current_state="$(echo "$(get_circuit_breaker_state)" | grep -o '"state":"[^"]*"' | cut -d'"' -f4)"
if [[ "${current_state}" == "OPEN" || "${current_state}" == "HALF_OPEN" ]]; then
  log_success "Circuit breaker reacted (state: ${current_state})."
else
  log_warn "Circuit breaker did not transition as expected (state: ${current_state:-unknown})."
fi

log_info "Step 5: restarting Kafka broker."
docker compose start kafka >/dev/null 2>&1 || true
sleep 15
log_success "Kafka broker restarted."

log_info "Step 6: sending recovery request."
if send_payment_request "RECOVERY_TEST" 10; then
  log_info "  recovery request completed."
else
  log_warn "  recovery request failed."
fi
sleep 3
print_state "After recovery traffic"

final_payload="$(get_circuit_breaker_state)"
final_state="$(echo "${final_payload}" | grep -o '"state":"[^"]*"' | cut -d'"' -f4)"
final_successful="$(echo "${final_payload}" | grep -o '"numberOfSuccessfulCalls":[0-9]*' | cut -d':' -f2)"

echo ""
echo "=============================================================="
echo " Test complete"
echo "=============================================================="
echo ""

if [[ -n "${final_successful}" && "${final_successful}" -gt 0 ]]; then
  log_success "Circuit breaker scenario finished. Final state: ${final_state:-unknown}"
  exit 0
fi

log_warn "Circuit breaker scenario finished, but success metrics were not recorded."
log_warn "Inspect ingest-service logs for details (docker compose logs ingest-service)."
exit 1
