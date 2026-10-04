package com.checkout.payment.gateway.exception;

import java.util.UUID;

public class IdempotencyConflictException extends RuntimeException {

  public IdempotencyConflictException(UUID originalPaymentId) {
    super("Payment " + originalPaymentId + " with the same Idempotency-Key is still in progress");
  }

  public IdempotencyConflictException() {
    super("Concurrent requests kept contending for the same Idempotency-Key");
  }
}
