package com.example.payment.domain;

/**
 * 결제 상태 (실제 PG사 구조 반영)
 *
 * 승인 단계: READY → AUTHORIZED / AUTH_FAILED
 * 정산 단계: CAPTURE_REQUESTED → CAPTURED / CAPTURE_FAILED
 * 환불 단계: REFUND_REQUESTED → REFUNDED / REFUND_FAILED
 */
public enum PaymentStatus {
    // 승인 단계
    READY,              // 결제 준비 (프론트엔드에서 결제 시도 전)
    AUTHORIZED,         // 승인 완료 (PG 승인 성공)
    AUTH_FAILED,        // 승인 실패

    // 정산 단계 (매입 확정)
    CAPTURE_REQUESTED,  // 정산 요청됨 (이벤트 발행됨)
    CAPTURED,           // 정산 완료 (PG 매입 확정)
    CAPTURE_FAILED,     // 정산 실패 (재시도 필요)

    // 환불 단계
    REFUND_REQUESTED,   // 환불 요청됨 (이벤트 발행됨)
    REFUNDED,           // 환불 완료
    REFUND_FAILED,      // 환불 실패

    // 부분 환불
    PARTIAL_REFUNDED,   // 부분 환불 완료

    // 레거시 호환 (삭제 예정)
    @Deprecated
    REQUESTED,          // → AUTHORIZED로 대체
    @Deprecated
    COMPLETED,          // → CAPTURED로 대체
    @Deprecated
    CANCELLED           // → REFUNDED로 대체
}
