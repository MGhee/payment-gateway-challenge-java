package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.exception.AcquiringBankException;
import com.checkout.payment.gateway.exception.BankOutcomeUnknownException;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

@Component
public class BankClient {

  private final RestTemplate restTemplate;

  public BankClient(RestTemplate restTemplate) {
    this.restTemplate = restTemplate;
  }

  public BankPaymentResponse authorize(UUID reference, PostPaymentRequest request) {
    BankPaymentResponse response;
    try {
      response = restTemplate.postForObject(
          "/payments", BankPaymentRequest.from(reference, request), BankPaymentResponse.class);
    } catch (RestClientResponseException e) {
      throw new AcquiringBankException("Acquiring bank returned " + e.getStatusCode(), e);
    } catch (ResourceAccessException e) {
      if (e.getCause() instanceof ConnectException || e.getCause() instanceof UnknownHostException) {
        throw new AcquiringBankException("Acquiring bank unreachable", e);
      }
      // The request may have reached the bank, e.g. a read timeout
      throw new BankOutcomeUnknownException("No response from acquiring bank", e);
    } catch (RestClientException e) {
      throw new BankOutcomeUnknownException("Unreadable response from acquiring bank", e);
    }
    if (response == null) {
      throw new BankOutcomeUnknownException("Empty response from acquiring bank");
    }
    return response;
  }

  public void reverse(UUID reference) {
    try {
      restTemplate.postForEntity("/reversals", new BankReversalRequest(reference), Void.class);
    } catch (RestClientException e) {
      throw new AcquiringBankException("Reversal failed: " + e.getMessage(), e);
    }
  }
}
