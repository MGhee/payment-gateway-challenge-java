package com.checkout.payment.gateway.configuration;

import java.time.Clock;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class ApplicationConfiguration {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public RestTemplate bankRestTemplate(RestTemplateBuilder builder, BankProperties bank) {
    return builder
        .rootUri(bank.url())
        .setConnectTimeout(bank.connectTimeout())
        .setReadTimeout(bank.readTimeout())
        .build();
  }
}
