package com.example.payment.web;

import com.example.payment.service.PaymentResult;
import com.example.payment.service.PaymentService;
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
        PaymentResult result = paymentService.authorize(request);
        if (result.duplicate()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("DUPLICATE_REQUEST",
                            "Idempotency key already used",
                            result.response().paymentId()));
        }
        return ResponseEntity.ok(result.response());
    }

    @PostMapping("/capture/{paymentId}")
    public ResponseEntity<?> capture(@PathVariable Long paymentId,
                                      @Valid @RequestBody CapturePaymentRequest request) {
        PaymentResult result = paymentService.capture(paymentId, request);
        if (result.duplicate()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("CAPTURE_CONFLICT",
                            result.response().message(),
                            result.response().paymentId()));
        }
        return ResponseEntity.ok(result.response());
    }

    @PostMapping("/refund/{paymentId}")
    public ResponseEntity<?> refund(@PathVariable Long paymentId,
                                     @Valid @RequestBody RefundPaymentRequest request) {
        PaymentResult result = paymentService.refund(paymentId, request);
        if (result.duplicate()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("REFUND_CONFLICT",
                            result.response().message(),
                            result.response().paymentId()));
        }
        return ResponseEntity.ok(result.response());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", ex.getMessage(), null));
    }
}
