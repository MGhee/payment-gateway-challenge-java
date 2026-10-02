package com.checkout.payment.gateway.model;

import com.checkout.payment.gateway.enums.PaymentStatus;
import java.util.UUID;

public record Payment(
    UUID id,
    PaymentStatus status,
    String cardNumberLastFour,
    int expiryMonth,
    int expiryYear,
    String currency,
    int amount,
    String idempotencyKey) {

  public static Payment pending(PostPaymentRequest request, String idempotencyKey) {
    return new Payment(
        UUID.randomUUID(),
        PaymentStatus.PENDING,
        request.cardNumberLastFour(),
        request.expiryMonth(),
        request.expiryYear(),
        request.currency(),
        request.amount(),
        idempotencyKey);
  }

  public Payment withStatus(PaymentStatus newStatus) {
    return new Payment(id, newStatus, cardNumberLastFour, expiryMonth, expiryYear, currency, amount,
        idempotencyKey);
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
