package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.exception.AcquiringBankException;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class BankClient {

  private final RestTemplate restTemplate;

  public BankClient(RestTemplate restTemplate) {
    this.restTemplate = restTemplate;
  }

  public BankPaymentResponse authorize(PostPaymentRequest request) {
    BankPaymentResponse response;
    try {
      response = restTemplate.postForObject(
          "/payments", BankPaymentRequest.from(request), BankPaymentResponse.class);
    } catch (RestClientException e) {
      throw new AcquiringBankException("Acquiring bank call failed", e);
    }
    if (response == null) {
      throw new AcquiringBankException("Acquiring bank returned an empty response");
    }
    return response;
  }
}
