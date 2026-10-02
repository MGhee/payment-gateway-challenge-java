package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BankPaymentRequest(
    UUID reference,
    String cardNumber,
    String expiryDate,
    String currency,
    int amount,
    String cvv) {

  static BankPaymentRequest from(UUID reference, PostPaymentRequest request) {
    return new BankPaymentRequest(
        reference,
        request.cardNumber(),
        "%02d/%d".formatted(request.expiryMonth(), request.expiryYear()),
        request.currency(),
        request.amount(),
        request.cvv());
  }

  // RestTemplate debug logging prints request bodies, so keep card data out of toString
  @Override
  public String toString() {
    return "BankPaymentRequest[reference=%s, cardNumber=****, expiryDate=%s, currency=%s, amount=%s, cvv=***]"
        .formatted(reference, expiryDate, currency, amount);
  }
}
