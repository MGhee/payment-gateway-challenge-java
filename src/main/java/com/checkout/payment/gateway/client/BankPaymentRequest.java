package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BankPaymentRequest(
    String cardNumber,
    String expiryDate,
    String currency,
    int amount,
    String cvv) {

  static BankPaymentRequest from(PostPaymentRequest request) {
    return new BankPaymentRequest(
        request.cardNumber(),
        "%02d/%d".formatted(request.expiryMonth(), request.expiryYear()),
        request.currency(),
        request.amount(),
        request.cvv());
  }

  // RestTemplate debug logging prints request bodies, so keep card data out of toString
  @Override
  public String toString() {
    return "BankPaymentRequest[cardNumber=****, expiryDate=%s, currency=%s, amount=%s, cvv=***]"
        .formatted(expiryDate, currency, amount);
  }
}
