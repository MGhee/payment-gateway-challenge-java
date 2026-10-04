package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.exception.AcquiringBankException;
import com.checkout.payment.gateway.exception.BankCallRejectedException;
import com.checkout.payment.gateway.exception.BankOutcomeUnknownException;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

@Component
public class BankClient {

  private final RestTemplate restTemplate;
  private final CircuitBreaker circuitBreaker;
  private final Bulkhead bulkhead;

  @Autowired
  public BankClient(RestTemplate restTemplate, CircuitBreaker circuitBreaker, Bulkhead bulkhead) {
    this.restTemplate = restTemplate;
    this.circuitBreaker = circuitBreaker;
    this.bulkhead = bulkhead;
  }

  public BankPaymentResponse authorize(UUID reference, PostPaymentRequest request) {
    Supplier<BankPaymentResponse> bankCall = CircuitBreaker.decorateSupplier(circuitBreaker,
        () -> authorizeOnce(reference, request));
    try {
      return Bulkhead.decorateSupplier(bulkhead, bankCall).get();
    } catch (CallNotPermittedException | BulkheadFullException e) {
      throw new BankCallRejectedException(e);
    }
  }

  private BankPaymentResponse authorizeOnce(UUID reference, PostPaymentRequest request) {
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
    Runnable bankCall = CircuitBreaker.decorateRunnable(circuitBreaker,
        () -> reverseOnce(reference));
    try {
      Bulkhead.decorateRunnable(bulkhead, bankCall).run();
    } catch (CallNotPermittedException | BulkheadFullException e) {
      throw new BankCallRejectedException(e);
    }
  }

  private void reverseOnce(UUID reference) {
    try {
      restTemplate.postForEntity("/reversals", new BankReversalRequest(reference), Void.class);
    } catch (RestClientException e) {
      throw new AcquiringBankException("Reversal failed: " + e.getMessage(), e);
    }
  }
}
