package com.checkout.payment.gateway.model;

import com.checkout.payment.gateway.enums.PaymentStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public record Payment(
    UUID id,
    String merchantId,
    PaymentStatus status,
    String cardNumberLastFour,
    int expiryMonth,
    int expiryYear,
    String currency,
    int amount,
    String idempotencyKey,
    Instant createdAt,
    boolean authorizationInProgress,
    int reversalAttempts,
    Instant nextReversalAt) {

  public static Payment pending(PostPaymentRequest request, String idempotencyKey,
      String merchantId) {
    return new Payment(
        UUID.randomUUID(),
        merchantId,
        PaymentStatus.PENDING,
        request.cardNumberLastFour(),
        request.expiryMonth(),
        request.expiryYear(),
        request.currency(),
        request.amount(),
        idempotencyKey,
        Instant.now().truncatedTo(ChronoUnit.MICROS),
        true,
        0,
        null);
  }

  public Payment withStatus(PaymentStatus newStatus) {
    return new Payment(id, merchantId, newStatus, cardNumberLastFour, expiryMonth, expiryYear,
        currency, amount, idempotencyKey, createdAt, false, reversalAttempts, null);
  }

  public Payment withUnknownOutcome(Instant nextAttemptAt) {
    return new Payment(id, merchantId, status, cardNumberLastFour, expiryMonth, expiryYear, currency,
        amount, idempotencyKey, createdAt, false, reversalAttempts, nextAttemptAt);
  }

  public Payment withReversalFailure(int attempts, Instant nextAttemptAt) {
    return new Payment(id, merchantId, status, cardNumberLastFour, expiryMonth, expiryYear, currency,
        amount, idempotencyKey, createdAt, false, attempts, nextAttemptAt);
  }

  // Card number and CVV are never stored, so a retry is matched on the fields we keep
  public boolean matches(PostPaymentRequest request) {
    return cardNumberLastFour.equals(request.cardNumberLastFour())
        && expiryMonth == request.expiryMonth()
        && expiryYear == request.expiryYear()
        && currency.equals(request.currency())
        && amount == request.amount();
  }
}
