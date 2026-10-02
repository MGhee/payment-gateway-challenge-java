package com.checkout.payment.gateway.model;

import com.checkout.payment.gateway.validation.FutureExpiryDate;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.Range;

@FutureExpiryDate
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PostPaymentRequest(
    @Schema(example = "2222405343248877")
    @NotNull @Pattern(regexp = "\\d{14,19}", message = "must be 14-19 digits")
    String cardNumber,

    @Schema(example = "4")
    @NotNull @Range(min = 1, max = 12)
    Integer expiryMonth,

    @Schema(example = "2030")
    @NotNull
    Integer expiryYear,

    @Schema(example = "GBP")
    @NotNull @Pattern(regexp = "USD|GBP|EUR", message = "must be one of USD, GBP, EUR")
    String currency,

    @Schema(description = "Amount in the minor currency unit, e.g. 1050 for 10.50", example = "100")
    @NotNull @Positive
    Integer amount,

    @Schema(example = "123")
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
