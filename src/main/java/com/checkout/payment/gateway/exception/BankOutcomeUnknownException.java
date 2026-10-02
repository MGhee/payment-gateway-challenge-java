package com.checkout.payment.gateway.exception;

public class BankOutcomeUnknownException extends RuntimeException {

  public BankOutcomeUnknownException(String message) {
    super(message);
  }

  public BankOutcomeUnknownException(String message, Throwable cause) {
    super(message, cause);
  }
}
