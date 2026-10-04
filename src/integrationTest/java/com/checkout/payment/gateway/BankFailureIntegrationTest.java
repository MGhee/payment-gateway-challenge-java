package com.checkout.payment.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.checkout.payment.gateway.service.PaymentReversalJob;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Real sockets against a fault-injecting bank, which the Mountebank simulator cannot do
class BankFailureIntegrationTest extends IntegrationTestBase {

  private static final String CARD = "2222405343248877";
  private static final String AUTHORIZED = """
      {"authorized": true, "authorization_code": "auth-code"}
      """;

  static final WireMockServer BANK = new WireMockServer(options().dynamicPort());

  static {
    BANK.start();
  }

  @DynamicPropertySource
  static void bank(DynamicPropertyRegistry registry) {
    // localhost resolves to several addresses, like a load-balanced bank host
    registry.add("bank.url", () -> "http://localhost:" + BANK.port());
    registry.add("bank.read-timeout", () -> "500ms");
  }

  @Autowired
  private PaymentReversalJob reversalJob;

  @BeforeEach
  void resetBank() {
    BANK.resetAll();
  }

  @Test
  void readTimeoutLeavesThePaymentPendingUntilItIsReversed() {
    BANK.stubFor(post("/payments").willReturn(okJson(AUTHORIZED).withFixedDelay(2_000)));
    BANK.stubFor(post("/reversals").willReturn(ok()));
    Merchant merchant = provisionMerchant();

    ResponseEntity<JsonNode> accepted = postPayment(merchant, CARD, null);

    assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(status(accepted)).isEqualTo("Pending");
    String id = accepted.getBody().path("id").asText();

    reversalJob.reversePendingPayments();

    BANK.verify(postRequestedFor(urlEqualTo("/reversals"))
        .withRequestBody(matchingJsonPath("$.reference", equalTo(id))));
    assertThat(status(getPayment(merchant, accepted.getHeaders().getLocation())))
        .isEqualTo("Declined");
  }

  @Test
  void connectionResetAfterSendingIsAnUnknownOutcomeAndIsNeverResent() {
    BANK.stubFor(post("/payments")
        .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    Merchant merchant = provisionMerchant();

    ResponseEntity<JsonNode> response = postPayment(merchant, CARD, null);

    BANK.verify(1, postRequestedFor(urlEqualTo("/payments")));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(status(response)).isEqualTo("Pending");
  }

  @Test
  void concurrentDuplicatesReachTheBankOnce() throws Exception {
    BANK.stubFor(post("/payments").willReturn(okJson(AUTHORIZED).withFixedDelay(300)));
    Merchant merchant = provisionMerchant();
    String key = UUID.randomUUID().toString();
    int requests = 8;
    ExecutorService clients = Executors.newFixedThreadPool(requests);
    CountDownLatch start = new CountDownLatch(1);
    List<ResponseEntity<JsonNode>> responses = new ArrayList<>();
    try {
      List<Future<ResponseEntity<JsonNode>>> pending = new ArrayList<>();
      for (int i = 0; i < requests; i++) {
        pending.add(clients.submit(() -> {
          start.await();
          return postPayment(merchant, CARD, key);
        }));
      }
      start.countDown();
      for (Future<ResponseEntity<JsonNode>> response : pending) {
        responses.add(response.get(30, TimeUnit.SECONDS));
      }
    } finally {
      clients.shutdownNow();
    }

    BANK.verify(1, postRequestedFor(urlEqualTo("/payments")));
    assertThat(storedPayments(merchant)).isOne();
    assertThat(responses).extracting(ResponseEntity::getStatusCode)
        .containsOnly(HttpStatus.CREATED, HttpStatus.CONFLICT)
        .contains(HttpStatus.CREATED);
    assertThat(responses).filteredOn(response -> response.getStatusCode() == HttpStatus.CREATED)
        .extracting(response -> response.getBody().path("id").asText())
        .containsOnly(responses.stream()
            .filter(response -> response.getStatusCode() == HttpStatus.CREATED)
            .findFirst().orElseThrow().getBody().path("id").asText());
  }
}
