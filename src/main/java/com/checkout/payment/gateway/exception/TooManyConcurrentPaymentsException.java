package com.checkout.payment.gateway.exception;

public class TooManyConcurrentPaymentsException extends RuntimeException {

  public TooManyConcurrentPaymentsException(String merchantId) {
    super("Merchant " + merchantId + " reached its concurrent payment limit");
  }
}
