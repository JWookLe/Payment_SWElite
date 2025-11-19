package com.example.payment.web;

import com.example.payment.config.shard.ShardContextHolder;
import com.example.payment.service.PaymentService;
import com.example.payment.service.PaymentResult;
import com.example.payment.service.RateLimitExceededException;
import com.example.payment.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/authorize")
    public ResponseEntity<?> authorize(@Valid @RequestBody AuthorizePaymentRequest request) {
        // 트랜잭션 시작 전에 샤드 선택 (중요!)
        ShardContextHolder.setShardByMerchantId(request.merchantId());
        try {
            PaymentResult result = paymentService.authorize(request);
            if (result.duplicate()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new ErrorResponse("DUPLICATE_REQUEST",
                                "Idempotency key already used",
                                result.response().paymentId()));
            }
            return ResponseEntity.ok(result.response());
        } finally {
            ShardContextHolder.clear();
        }
    }

    @PostMapping("/capture/{paymentId}")
    public ResponseEntity<?> capture(@PathVariable Long paymentId,
                                      @Valid @RequestBody CapturePaymentRequest request) {
        // 트랜잭션 시작 전에 샤드 선택 (중요!)
        ShardContextHolder.setShardByMerchantId(request.merchantId());
        try {
            PaymentResult result = paymentService.capture(paymentId, request);
            if (result.duplicate()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new ErrorResponse("CAPTURE_CONFLICT",
                                result.response().message(),
                                result.response().paymentId()));
            }
            return ResponseEntity.ok(result.response());
        } finally {
            ShardContextHolder.clear();
        }
    }

    @PostMapping("/refund/{paymentId}")
    public ResponseEntity<?> refund(@PathVariable Long paymentId,
                                     @Valid @RequestBody RefundPaymentRequest request) {
        // 트랜잭션 시작 전에 샤드 선택 (중요!)
        ShardContextHolder.setShardByMerchantId(request.merchantId());
        try {
            PaymentResult result = paymentService.refund(paymentId, request);
            if (result.duplicate()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new ErrorResponse("REFUND_CONFLICT",
                                result.response().message(),
                                result.response().paymentId()));
            }
            return ResponseEntity.ok(result.response());
        } finally {
            ShardContextHolder.clear();
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", ex.getMessage(), null));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitExceeded(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ErrorResponse(ex.getCode(), ex.getMessage(), null));
    }
}
