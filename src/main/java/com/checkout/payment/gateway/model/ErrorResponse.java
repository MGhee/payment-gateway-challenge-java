package com.checkout.payment.gateway.model;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(PaymentStatus status, String message, List<String> errors) {

  public static ErrorResponse rejected(String message, List<String> errors) {
    return new ErrorResponse(PaymentStatus.REJECTED, message, errors);
  }

  public static ErrorResponse of(String message) {
    return new ErrorResponse(null, message, null);
  }
}
