package com.checkout.payment.gateway.exception;

// The call never left the gateway: the circuit breaker is open or the bank bulkhead is full
public class BankCallRejectedException extends AcquiringBankException {

  public BankCallRejectedException(Throwable cause) {
    super("Acquiring bank temporarily unavailable", cause);
  }
}
