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

import com.checkout.payment.gateway.service.PaymentReversalJob;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class BankFailureIntegrationTest extends WireMockBankTestBase {

  @DynamicPropertySource
  static void shortReadTimeout(DynamicPropertyRegistry registry) {
    registry.add("bank.read-timeout", () -> "500ms");
  }

  @Autowired
  private PaymentReversalJob reversalJob;

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
}
