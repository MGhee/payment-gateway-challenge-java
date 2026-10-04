package com.checkout.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

// Runs against the same Mountebank imposter as docker-compose, so contract drift fails the build
class BankSimulatorIntegrationTest extends IntegrationTestBase {

  static final GenericContainer<?> SIMULATOR = new GenericContainer<>("bbyars/mountebank:2.8.1")
      .withCopyFileToContainer(MountableFile.forHostPath("imposters/bank_simulator.ejs"),
          "/imposters/bank_simulator.ejs")
      .withCommand("--configfile", "/imposters/bank_simulator.ejs", "--allowInjection")
      .withExposedPorts(8080)
      .waitingFor(Wait.forListeningPort());

  static {
    SIMULATOR.start();
  }

  @DynamicPropertySource
  static void bankUrl(DynamicPropertyRegistry registry) {
    registry.add("bank.url",
        () -> "http://" + SIMULATOR.getHost() + ":" + SIMULATOR.getMappedPort(8080));
  }

  @ParameterizedTest
  @CsvSource({"2222405343248877, Authorized, AUTHORIZED", "2222405343248878, Declined, DECLINED"})
  void bankOutcomeIsReturnedAndPersisted(String cardNumber, String status, String storedStatus) {
    Merchant merchant = provisionMerchant();

    ResponseEntity<JsonNode> created = postPayment(merchant, cardNumber, null);

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(status(created)).isEqualTo(status);
    ResponseEntity<JsonNode> fetched = getPayment(merchant, created.getHeaders().getLocation());
    assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(fetched.getBody()).isEqualTo(created.getBody());
    Map<String, Object> row = jdbc.queryForMap("SELECT status, card_number_last_four, "
            + "authorization_in_progress FROM payments WHERE id = ?",
        UUID.fromString(created.getBody().path("id").asText()));
    assertThat(row).containsEntry("status", storedStatus)
        .containsEntry("card_number_last_four", cardNumber.substring(12))
        .containsEntry("authorization_in_progress", false);
  }

  @Test
  void bankErrorStoresNothingAndFreesTheIdempotencyKey() {
    Merchant merchant = provisionMerchant();
    String key = UUID.randomUUID().toString();

    ResponseEntity<JsonNode> failed = postPayment(merchant, "2222405343248870", key);

    assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(storedPayments(merchant)).isZero();
    ResponseEntity<JsonNode> retried = postPayment(merchant, "2222405343248877", key);
    assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(storedPayments(merchant)).isOne();
  }
}
