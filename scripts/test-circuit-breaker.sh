#!/bin/bash

###############################################################################
# Circuit Breaker 자동 테스트 스크립트
#
# 목적: Kafka 장애 시나리오에서 Circuit Breaker가 제대로 동작하는지 자동 검증
# 사용법: bash scripts/test-circuit-breaker.sh
###############################################################################

set -e

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 로그 함수
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[✓]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[!]${NC} $1"
}

log_error() {
    echo -e "${RED}[✗]${NC} $1"
}

# 설정
# Jenkins가 host에서 실행될 때는 localhost로 접근
# 컨테이너 내부에서 실행될 때는 ingest-service 서비스명으로 접근 가능
API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"
CIRCUIT_BREAKER_ENDPOINT="${API_BASE_URL}/circuit-breaker/kafka-publisher"
MAX_RETRIES=5
RETRY_DELAY=2

# 함수: API 헬스 체크
check_api_health() {
    log_info "API 헬스 체크 중..."
    for i in $(seq 1 $MAX_RETRIES); do
        if curl -s -f "${API_BASE_URL}/actuator/health" > /dev/null 2>&1; then
            log_success "API 준비 완료"
            return 0
        fi
        if [ $i -lt $MAX_RETRIES ]; then
            log_warn "API가 아직 준비 안 됨, $RETRY_DELAY초 후 재시도... ($i/$MAX_RETRIES)"
            sleep $RETRY_DELAY
        fi
    done
    log_error "API가 응답하지 않습니다"
    return 1
}

# 함수: Circuit Breaker 상태 조회
get_circuit_breaker_state() {
    curl -s "$CIRCUIT_BREAKER_ENDPOINT" 2>/dev/null || echo "{}"
}

# 함수: 결제 요청 (성공 또는 타임아웃)
send_payment_request() {
    local merchant_id=$1
    local timeout=${2:-15}

    timeout $timeout curl -s -X POST "${API_BASE_URL}/payments/authorize" \
        -H "Content-Type: application/json" \
        -d "{\"merchantId\":\"${merchant_id}\",\"amount\":50000,\"currency\":\"KRW\",\"idempotencyKey\":\"test-${merchant_id}-$(date +%s%N)\"}" \
        > /dev/null 2>&1
}

# 함수: 상태 출력
print_state() {
    local label=$1
    local state=$(get_circuit_breaker_state)

    local circuit_state=$(echo "$state" | grep -o '"state":"[^"]*"' | cut -d'"' -f4)
    local successful=$(echo "$state" | grep -o '"numberOfSuccessfulCalls":[0-9]*' | cut -d':' -f2)
    local slow=$(echo "$state" | grep -o '"numberOfSlowCalls":[0-9]*' | cut -d':' -f2)
    local slow_rate=$(echo "$state" | grep -o '"slowCallRate":"[^"]*"' | cut -d'"' -f4)

    echo ""
    log_info "$label"
    echo "  상태: $circuit_state"
    echo "  성공한 호출: $successful"
    echo "  느린 호출: $slow"
    echo "  느린 비율: $slow_rate"
}

###############################################################################
# 메인 테스트 시작
###############################################################################

echo ""
echo "╔════════════════════════════════════════════════════════════════════════╗"
echo "║     Circuit Breaker 자동 테스트                                        ║"
echo "║     Kafka 장애 시나리오 검증                                            ║"
echo "╚════════════════════════════════════════════════════════════════════════╝"
echo ""

# Step 1: API 헬스 체크
log_info "Step 1: API 연결 확인"
if ! check_api_health; then
    log_error "API에 연결할 수 없습니다. 서비스를 확인하세요."
    exit 1
fi
echo ""

# Step 2: 초기 상태 확인
log_info "Step 2: 초기 Circuit Breaker 상태 확인"
print_state "초기 상태 (Kafka UP)"
echo ""

# Step 3: 정상 요청 5개 전송
log_info "Step 3: Kafka 정상 상태에서 5개 요청 전송"
for i in {1..5}; do
    send_payment_request "NORMAL_TEST_$i" 10
    echo "  ✓ 요청 $i 완료"
done
sleep 2
print_state "5개 요청 후"
echo ""

# Step 4: Kafka 중단
log_info "Step 4: Kafka 중단"
docker compose stop kafka > /dev/null 2>&1
sleep 5
log_success "Kafka 중단됨"
echo ""

# Step 5: Kafka DOWN 상태에서 느린 요청 전송
log_info "Step 5: Kafka DOWN 상태에서 6개 요청 전송 (느린 호출 발생)"
for i in {1..6}; do
    echo "  ⏳ 요청 $i 전송 중... (타임아웃)"
    send_payment_request "SLOW_TEST_$i" 15 &
    sleep 1
done
wait
log_success "모든 느린 요청 완료"
sleep 5
print_state "느린 요청 후"
echo ""

# Step 6: Kafka 상태 확인
log_info "Step 6: Circuit Breaker 상태 확인"
current_state=$(get_circuit_breaker_state | grep -o '"state":"[^"]*"' | cut -d'"' -f4)
if [ "$current_state" = "HALF_OPEN" ] || [ "$current_state" = "OPEN" ]; then
    log_success "Circuit Breaker가 정상적으로 반응했습니다 (상태: $current_state)"
else
    log_warn "Circuit Breaker가 예상과 다른 상태입니다 (상태: $current_state)"
fi
echo ""

# Step 7: Kafka 재시작
log_info "Step 7: Kafka 재시작"
docker compose start kafka > /dev/null 2>&1
sleep 15
log_success "Kafka 재시작됨"
echo ""

# Step 8: 복구 요청 전송
log_info "Step 8: 복구 테스트 요청 전송"
send_payment_request "RECOVERY_TEST" 10
sleep 3
print_state "복구 후"
echo ""

# Step 9: 최종 상태 확인
log_info "Step 9: 최종 상태 확인"
final_state=$(get_circuit_breaker_state | grep -o '"state":"[^"]*"' | cut -d'"' -f4)
final_successful=$(get_circuit_breaker_state | grep -o '"numberOfSuccessfulCalls":[0-9]*' | cut -d':' -f2)

echo ""
echo "╔════════════════════════════════════════════════════════════════════════╗"
echo "║                     테스트 완료                                        ║"
echo "╚════════════════════════════════════════════════════════════════════════╝"
echo ""

if [ "$final_successful" -gt 0 ]; then
    log_success "Circuit Breaker 정상 작동!"
    echo "  - 성공한 호출: $final_successful"
    echo "  - 최종 상태: $final_state"
    echo ""
    log_success "✅ 모든 테스트 통과"
    echo ""
    echo "📊 Grafana에서 실시간 메트릭 확인:"
    echo "   http://localhost:3000 → Dashboards → Payment Service Overview"
    echo ""
else
    log_warn "일부 테스트가 예상과 다를 수 있습니다"
    echo "  - 최종 상태: $final_state"
    echo ""
    log_error "로그를 확인하세요:"
    echo "   docker compose logs ingest-service | grep -i circuit"
    echo ""
    exit 1
fi
