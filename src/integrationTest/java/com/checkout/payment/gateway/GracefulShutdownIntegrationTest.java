package com.checkout.payment.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;

// Starts its own gateway per test, because the shared Spring test context cannot be shut down
class GracefulShutdownIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES = IntegrationTestBase.POSTGRES;
  private static final WireMockServer BANK = WireMockBankTestBase.BANK;
  // Own database, so the reversal job only sees this class's payments
  private static final String DATABASE_URL = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
      + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/shutdown_test";

  private final String merchantId = "it-shutdown-" + UUID.randomUUID();
  private final JdbcTemplate jdbc = new JdbcTemplate(dataSource(DATABASE_URL));
  private ConfigurableApplicationContext gateway;
  private RestClient http;

  @BeforeAll
  static void createDatabase() {
    new JdbcTemplate(dataSource(POSTGRES.getJdbcUrl()))
        .execute("DROP DATABASE IF EXISTS shutdown_test");
    new JdbcTemplate(dataSource(POSTGRES.getJdbcUrl())).execute("CREATE DATABASE shutdown_test");
  }

  @BeforeEach
  void startGateway() {
    BANK.resetAll();
    gateway = new SpringApplicationBuilder(PaymentGatewayApplication.class).run(
        "--server.port=0",
        "--management.server.port=0",
        "--spring.datasource.url=" + DATABASE_URL,
        "--spring.datasource.username=" + POSTGRES.getUsername(),
        "--spring.datasource.password=" + POSTGRES.getPassword(),
        "--bank.url=http://localhost:" + BANK.port(),
        "--gateway.admin-api-key=" + IntegrationTestBase.ADMIN_KEY,
        "--bank.reversal-retry-interval=PT0.5S");
    int port = ((WebServerApplicationContext) gateway).getWebServer().getPort();
    http = RestClient.create("http://localhost:" + port);
  }

  @AfterEach
  void stopGateway() {
    gateway.close();
  }

  @Test
  void paymentInFlightWhenShutdownStartsStillGetsItsAnswer() throws Exception {
    BANK.stubFor(post("/payments").willReturn(
        okJson(WireMockBankTestBase.AUTHORIZED).withFixedDelay(2_000)));
    String apiKey = provisionMerchant();
    CompletableFuture<ResponseEntity<JsonNode>> payment =
        CompletableFuture.supplyAsync(() -> postPayment(apiKey));
    await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
        "SELECT COUNT(*) FROM payments WHERE merchant_id = ? AND authorization_in_progress",
        Integer.class, merchantId) == 1);

    gateway.close();

    ResponseEntity<JsonNode> response = payment.get(10, TimeUnit.SECONDS);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody().path("status").asText()).isEqualTo("Authorized");
  }

  @Test
  void reversalRunningWhenShutdownStartsIsCompleted() {
    BANK.stubFor(post("/payments")
        .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    BANK.stubFor(post("/reversals").willReturn(ok().withFixedDelay(2_000)));
    String apiKey = provisionMerchant();
    UUID id = UUID.fromString(postPayment(apiKey).getBody().path("id").asText());
    // A lease far in the future means the reversal job has claimed the payment and is calling the bank
    await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
        "SELECT COALESCE(next_reversal_at > NOW() + INTERVAL '60 seconds', FALSE) "
            + "FROM payments WHERE id = ?", Boolean.class, id));

    gateway.close();

    assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, id))
        .isEqualTo("DECLINED");
  }

  private static DriverManagerDataSource dataSource(String url) {
    return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private String provisionMerchant() {
    return http.post().uri("/admin/merchants/{id}/api-keys", merchantId)
        .header("X-Gateway-Admin-Key", IntegrationTestBase.ADMIN_KEY)
        .retrieve().body(JsonNode.class).path("api_key").asText();
  }

  private ResponseEntity<JsonNode> postPayment(String apiKey) {
    return http.post().uri("/v1/payments")
        .header("X-API-Key", apiKey)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("card_number", WireMockBankTestBase.CARD, "expiry_month", 4,
            "expiry_year", IntegrationTestBase.NEXT_YEAR, "currency", "GBP", "amount", 100,
            "cvv", "123"))
        .retrieve().toEntity(JsonNode.class);
  }
}
