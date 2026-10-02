package com.checkout.payment.gateway.exception;

import java.util.UUID;

public class PaymentNotFoundException extends RuntimeException {

  public PaymentNotFoundException(UUID id) {
    super("Payment " + id + " not found");
  }
}
