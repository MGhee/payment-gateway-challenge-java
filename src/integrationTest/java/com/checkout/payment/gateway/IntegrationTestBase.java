package com.checkout.payment.gateway;

import java.net.URI;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "gateway.admin-api-key=" + IntegrationTestBase.ADMIN_KEY,
    "bank.reversal-retry-interval=PT1H",
    "bank.authorization-recovery-interval=PT1H",
    "gateway.idempotency-cleanup-interval=PT1H"})
public abstract class IntegrationTestBase {

  static final String ADMIN_KEY = "integration-admin-key";
  static final int NEXT_YEAR = Year.now(ZoneOffset.UTC).getValue() + 1;

  // Shared by every test class and never stopped, so cached Spring contexts stay connected
  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired
  protected TestRestTemplate http;
  @Autowired
  protected JdbcTemplate jdbc;
  @Autowired
  private CircuitBreaker bankCircuitBreaker;

  @BeforeEach
  void closeBankCircuit() {
    bankCircuitBreaker.reset();
  }

  protected Merchant provisionMerchant() {
    String merchantId = "it-" + UUID.randomUUID();
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Gateway-Admin-Key", ADMIN_KEY);
    ResponseEntity<JsonNode> response = http.exchange("/admin/merchants/{id}/api-keys",
        HttpMethod.POST, new HttpEntity<>(headers), JsonNode.class, merchantId);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return new Merchant(merchantId, response.getBody().path("api_key").asText());
  }

  protected ResponseEntity<JsonNode> postPayment(Merchant merchant, String cardNumber,
      String idempotencyKey) {
    HttpHeaders headers = merchantHeaders(merchant);
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (idempotencyKey != null) {
      headers.set("Idempotency-Key", idempotencyKey);
    }
    Map<String, Object> body = Map.of("card_number", cardNumber, "expiry_month", 4,
        "expiry_year", NEXT_YEAR, "currency", "GBP", "amount", 100, "cvv", "123");
    return http.exchange("/v1/payments", HttpMethod.POST, new HttpEntity<>(body, headers),
        JsonNode.class);
  }

  protected ResponseEntity<JsonNode> getPayment(Merchant merchant, URI location) {
    return http.exchange(location.toString(), HttpMethod.GET,
        new HttpEntity<>(merchantHeaders(merchant)), JsonNode.class);
  }

  protected int storedPayments(Merchant merchant) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE merchant_id = ?",
        Integer.class, merchant.id());
  }

  protected static String status(ResponseEntity<JsonNode> response) {
    return response.getBody().path("status").asText();
  }

  private static HttpHeaders merchantHeaders(Merchant merchant) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-API-Key", merchant.apiKey());
    return headers;
  }

  protected record Merchant(String id, String apiKey) {
  }
}
