package com.checkout.payment.gateway.configuration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

class ApplicationConfigurationTest {

  @Test
  void bankUrlWithAnInvalidHostnameFailsAtStartup() {
    BankProperties bank = new BankProperties("http://bank_simulator:8080", Duration.ofSeconds(2),
        Duration.ofSeconds(10), 5, Duration.ofSeconds(10));

    assertThatThrownBy(() -> new ApplicationConfiguration()
        .bankRestTemplate(new RestTemplateBuilder(), bank))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bank_simulator");
  }
}
