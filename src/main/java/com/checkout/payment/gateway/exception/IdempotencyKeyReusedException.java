package com.checkout.payment.gateway.exception;

import java.util.UUID;

public class IdempotencyKeyReusedException extends RuntimeException {

  public IdempotencyKeyReusedException(UUID originalPaymentId) {
    super("Idempotency-Key of payment " + originalPaymentId + " reused for a different payment");
  }
}
