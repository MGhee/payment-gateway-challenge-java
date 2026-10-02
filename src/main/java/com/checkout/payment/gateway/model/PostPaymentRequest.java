package com.checkout.payment.gateway.model;

import com.checkout.payment.gateway.validation.FutureExpiryDate;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.Range;

@FutureExpiryDate
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PostPaymentRequest(
    @NotNull @Pattern(regexp = "\\d{14,19}", message = "must be 14-19 digits")
    String cardNumber,

    @NotNull @Range(min = 1, max = 12)
    Integer expiryMonth,

    @NotNull
    Integer expiryYear,

    @NotNull @Pattern(regexp = "USD|GBP|EUR", message = "must be one of USD, GBP, EUR")
    String currency,

    @NotNull @Positive
    Integer amount,

    @NotNull @Pattern(regexp = "\\d{3,4}", message = "must be 3-4 digits")
    String cvv) {

  public String cardNumberLastFour() {
    return cardNumber.substring(cardNumber.length() - 4);
  }

  // Card number and CVV must never end up in logs
  @Override
  public String toString() {
    return "PostPaymentRequest[cardNumber=****, expiryMonth=%s, expiryYear=%s, currency=%s, amount=%s, cvv=***]"
        .formatted(expiryMonth, expiryYear, currency, amount);
  }
}
