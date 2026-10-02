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
    int amount) {

  public static Payment pending(PostPaymentRequest request) {
    return new Payment(
        UUID.randomUUID(),
        PaymentStatus.PENDING,
        request.cardNumberLastFour(),
        request.expiryMonth(),
        request.expiryYear(),
        request.currency(),
        request.amount());
  }

  public Payment withStatus(PaymentStatus newStatus) {
    return new Payment(id, newStatus, cardNumberLastFour, expiryMonth, expiryYear, currency, amount);
  }
}
