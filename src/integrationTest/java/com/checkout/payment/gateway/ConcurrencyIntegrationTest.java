package com.checkout.payment.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.configuration.BankProperties;
import com.checkout.payment.gateway.repository.PaymentStore;
import com.checkout.payment.gateway.service.PaymentReversalJob;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.http.Fault;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ConcurrencyIntegrationTest extends WireMockBankTestBase {

  private static final int MERCHANT_LIMIT = 10;

  @Autowired
  private PaymentStore payments;
  @Autowired
  private PaymentReversalJob reversalJob;
  @Autowired
  private BankClient bankClient;
  @Autowired
  private MeterRegistry meterRegistry;
  @Autowired
  private BankProperties bankProperties;
  @Autowired
  private Clock clock;

  @Test
  void concurrentDuplicatesReachTheBankOnce() throws Exception {
    BANK.stubFor(post("/payments").willReturn(okJson(AUTHORIZED).withFixedDelay(300)));
    Merchant merchant = provisionMerchant();
    String key = UUID.randomUUID().toString();

    List<ResponseEntity<JsonNode>> responses =
        concurrently(8, () -> postPayment(merchant, CARD, key));

    BANK.verify(1, postRequestedFor(urlEqualTo("/payments")));
    assertThat(storedPayments(merchant)).isOne();
    assertThat(responses).extracting(ResponseEntity::getStatusCode)
        .containsOnly(HttpStatus.CREATED, HttpStatus.CONFLICT)
        .contains(HttpStatus.CREATED);
    assertThat(responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED)
        .map(ConcurrencyIntegrationTest::id).distinct()).hasSize(1);
  }

  @Test
  void burstFromOneMerchantIsCappedWithoutStarvingOtherMerchants() throws Exception {
    BANK.stubFor(post("/payments").willReturn(okJson(AUTHORIZED).withFixedDelay(2_000)));
    Merchant busy = provisionMerchant();
    Merchant other = provisionMerchant();

    CompletableFuture<List<ResponseEntity<JsonNode>>> burst = CompletableFuture.supplyAsync(() -> {
      try {
        return concurrently(MERCHANT_LIMIT + 5, () -> postPayment(busy, CARD, null));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
    await().atMost(Duration.ofSeconds(10)).until(() -> inFlight(busy) == MERCHANT_LIMIT);
    ResponseEntity<JsonNode> otherMerchant = postPayment(other, CARD, null);
    List<ResponseEntity<JsonNode>> responses = burst.get(60, TimeUnit.SECONDS);

    assertThat(otherMerchant.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED)
        .hasSize(MERCHANT_LIMIT);
    assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS)
        .hasSize(5)
        .allSatisfy(r -> assertThat(r.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1"));
    assertThat(storedPayments(busy)).isEqualTo(MERCHANT_LIMIT);
  }

  @Test
  void lateBankAnswerAfterARecoveryTakeoverIsReversedNotRecorded() throws Exception {
    BANK.stubFor(post("/payments").willReturn(okJson(AUTHORIZED).withFixedDelay(1_500)));
    BANK.stubFor(post("/reversals").willReturn(ok()));
    Merchant merchant = provisionMerchant();

    CompletableFuture<ResponseEntity<JsonNode>> request =
        CompletableFuture.supplyAsync(() -> postPayment(merchant, CARD, null));
    await().atMost(Duration.ofSeconds(10)).until(() -> inFlight(merchant) == 1);
    // As if the request had been stuck past the recovery job's threshold
    payments.recoverStaleAuthorizations(Instant.now().plusSeconds(1), Instant.now());
    ResponseEntity<JsonNode> response = request.get(30, TimeUnit.SECONDS);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(status(response)).isEqualTo("Pending");
    reversalJob.reversePendingPayments();
    BANK.verify(postRequestedFor(urlEqualTo("/reversals"))
        .withRequestBody(matchingJsonPath("$.reference", equalTo(id(response)))));
    assertThat(status(getPayment(merchant, response.getHeaders().getLocation())))
        .isEqualTo("Declined");
  }

  @Test
  void twoGatewayInstancesReverseAPaymentOnlyOnce() throws Exception {
    BANK.stubFor(post("/payments")
        .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    BANK.stubFor(post("/reversals").willReturn(ok().withFixedDelay(500)));
    Merchant merchant = provisionMerchant();
    ResponseEntity<JsonNode> pending = postPayment(merchant, CARD, null);
    assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    List<PaymentReversalJob> instances = List.of(reversalJob,
        new PaymentReversalJob(bankClient, payments, meterRegistry, bankProperties, clock));
    AtomicInteger next = new AtomicInteger();

    concurrently(instances.size(), () -> {
      instances.get(next.getAndIncrement()).reversePendingPayments();
      return null;
    });

    BANK.verify(1, postRequestedFor(urlEqualTo("/reversals"))
        .withRequestBody(matchingJsonPath("$.reference", equalTo(id(pending)))));
    assertThat(status(getPayment(merchant, pending.getHeaders().getLocation())))
        .isEqualTo("Declined");
  }

  private int inFlight(Merchant merchant) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE merchant_id = ? "
        + "AND authorization_in_progress = TRUE", Integer.class, merchant.id());
  }

  private static String id(ResponseEntity<JsonNode> response) {
    return response.getBody().path("id").asText();
  }
}
