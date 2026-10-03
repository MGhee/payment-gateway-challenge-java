package com.checkout.payment.gateway.service;

import java.util.UUID;

public class MerchantCredentialNotFoundException extends RuntimeException {

  public MerchantCredentialNotFoundException(UUID keyId) {
    super("Merchant API key " + keyId + " not found");
  }
}