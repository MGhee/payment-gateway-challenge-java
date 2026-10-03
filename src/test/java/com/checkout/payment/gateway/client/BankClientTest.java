package com.checkout.payment.gateway.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.checkout.payment.gateway.exception.AcquiringBankException;
import com.checkout.payment.gateway.exception.BankOutcomeUnknownException;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class BankClientTest {

  private static final UUID REFERENCE = UUID.fromString("6f1c2b9e-4d7a-4a5b-9c3e-1f2a3b4c5d6e");

  private final RestTemplate restTemplate = new RestTemplateBuilder().rootUri("http://bank").build();
  private final MockRestServiceServer bank = MockRestServiceServer.bindTo(restTemplate).build();
  private final BankClient bankClient = new BankClient(restTemplate,
      CircuitBreaker.ofDefaults("bank-test"), Bulkhead.ofDefaults("bank-test"));

  private final PostPaymentRequest request =
      new PostPaymentRequest("2222405343248877", 4, 2030, "GBP", 100, "123");

  @Test
  void sendsThePaymentInTheBankFormat() {
    bank.expect(requestTo("http://bank/payments"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().json("""
            {"reference": "6f1c2b9e-4d7a-4a5b-9c3e-1f2a3b4c5d6e",
             "card_number": "2222405343248877", "expiry_date": "04/2030",
             "currency": "GBP", "amount": 100, "cvv": "123"}
            """, true))
        .andRespond(withSuccess("""
            {"authorized": true, "authorization_code": "0bb07405-6d44-4b50-a14f-7ae0beff13ad"}
            """, MediaType.APPLICATION_JSON));

    BankPaymentResponse response = bankClient.authorize(REFERENCE, request);

    bank.verify();
    assertThat(response.authorized()).isTrue();
    assertThat(response.authorizationCode()).isEqualTo("0bb07405-6d44-4b50-a14f-7ae0beff13ad");
  }

  @Test
  void returnsUnauthorizedWhenTheBankDeclines() {
    bank.expect(requestTo("http://bank/payments"))
        .andRespond(withSuccess("""
            {"authorized": false, "authorization_code": ""}
            """, MediaType.APPLICATION_JSON));

    assertThat(bankClient.authorize(REFERENCE, request).authorized()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 500, 503})
  void bankErrorResponseMeansNothingWasAuthorized(int status) {
    bank.expect(requestTo("http://bank/payments"))
        .andRespond(withStatus(HttpStatusCode.valueOf(status)));

    assertThatThrownBy(() -> bankClient.authorize(REFERENCE, request))
        .isInstanceOf(AcquiringBankException.class);
  }

  @Test
  void unreachableBankMeansNothingWasAuthorized() {
    bank.expect(requestTo("http://bank/payments"))
        .andRespond(req -> {
          throw new ConnectException("Connection refused");
        });

    assertThatThrownBy(() -> bankClient.authorize(REFERENCE, request))
        .isInstanceOf(AcquiringBankException.class);
  }

  @Test
  void timeoutMakesTheOutcomeUnknown() {
    bank.expect(requestTo("http://bank/payments"))
        .andRespond(req -> {
          throw new SocketTimeoutException("Read timed out");
        });

    assertThatThrownBy(() -> bankClient.authorize(REFERENCE, request))
        .isInstanceOf(BankOutcomeUnknownException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not json"})
  void unreadableSuccessResponseMakesTheOutcomeUnknown(String body) {
    bank.expect(requestTo("http://bank/payments"))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> bankClient.authorize(REFERENCE, request))
        .isInstanceOf(BankOutcomeUnknownException.class);
  }

  @Test
  void reversesThePaymentByReference() {
    bank.expect(requestTo("http://bank/reversals"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().json("""
            {"reference": "6f1c2b9e-4d7a-4a5b-9c3e-1f2a3b4c5d6e"}
            """, true))
        .andRespond(withSuccess());

    bankClient.reverse(REFERENCE);

    bank.verify();
  }

  @Test
  void failedReversalIsReported() {
    bank.expect(requestTo("http://bank/reversals"))
        .andRespond(withStatus(HttpStatusCode.valueOf(400)));

    assertThatThrownBy(() -> bankClient.reverse(REFERENCE))
        .isInstanceOf(AcquiringBankException.class);
  }

      @Test
      void openCircuitRejectsAuthorizationWithoutCallingTheBank() {
      CircuitBreakerConfig config = CircuitBreakerConfig.custom()
        .slidingWindowSize(2)
        .minimumNumberOfCalls(2)
        .failureRateThreshold(50)
        .waitDurationInOpenState(Duration.ofMinutes(1))
        .build();
      BankClient guardedClient = new BankClient(restTemplate,
        CircuitBreaker.of("open-bank", config), Bulkhead.ofDefaults("open-bank"));
      bank.expect(requestTo("http://bank/payments"))
        .andRespond(withStatus(HttpStatusCode.valueOf(503)));
      bank.expect(requestTo("http://bank/payments"))
        .andRespond(withStatus(HttpStatusCode.valueOf(503)));

      assertThatThrownBy(() -> guardedClient.authorize(REFERENCE, request))
        .isInstanceOf(AcquiringBankException.class);
      assertThatThrownBy(() -> guardedClient.authorize(REFERENCE, request))
        .isInstanceOf(AcquiringBankException.class);
      assertThatThrownBy(() -> guardedClient.authorize(REFERENCE, request))
        .isInstanceOf(AcquiringBankException.class);

      bank.verify();
      }

      @Test
      void fullBulkheadRejectsAuthorizationWithoutCallingTheBank() {
      BulkheadConfig config = BulkheadConfig.custom()
        .maxConcurrentCalls(1)
        .maxWaitDuration(Duration.ZERO)
        .build();
      Bulkhead bulkhead = Bulkhead.of("full-bank", config);
      assertThat(bulkhead.tryAcquirePermission()).isTrue();
      BankClient guardedClient = new BankClient(restTemplate,
        CircuitBreaker.ofDefaults("full-bank"), bulkhead);

      assertThatThrownBy(() -> guardedClient.authorize(REFERENCE, request))
        .isInstanceOf(AcquiringBankException.class);

      bulkhead.releasePermission();
      bank.verify();
      }
}
