package com.checkout.payment.gateway.configuration;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableScheduling
public class ApplicationConfiguration {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public RestTemplate bankRestTemplate(RestTemplateBuilder builder, BankProperties bank) {
    if (URI.create(bank.url()).getHost() == null) {
      throw new IllegalStateException("bank.url has no valid hostname: " + bank.url());
    }
    // Pinned because OkHttp and HttpURLConnection silently resend a POST after a connection reset
    HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(bank.connectTimeout())
        .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(bank.readTimeout());
    return builder
        .rootUri(bank.url())
        .requestFactory(() -> requestFactory)
        .build();
  }

  @Bean
  public CircuitBreaker bankCircuitBreaker(
      @Value("${bank.circuit-breaker.sliding-window-size:10}") int slidingWindowSize,
      @Value("${bank.circuit-breaker.minimum-number-of-calls:5}") int minimumNumberOfCalls,
      @Value("${bank.circuit-breaker.failure-rate-threshold:50}") float failureRateThreshold,
      @Value("${bank.circuit-breaker.wait-duration-in-open-state:30s}") Duration waitDuration,
      @Value("${bank.circuit-breaker.permitted-calls-in-half-open-state:3}") int halfOpenCalls) {
    CircuitBreakerConfig config = CircuitBreakerConfig.custom()
        .slidingWindowSize(slidingWindowSize)
        .minimumNumberOfCalls(minimumNumberOfCalls)
        .failureRateThreshold(failureRateThreshold)
        .waitDurationInOpenState(waitDuration)
        .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
        .build();
    return CircuitBreaker.of("bank", config);
  }

  @Bean
  public Bulkhead bankBulkhead(
      @Value("${bank.bulkhead.max-concurrent-calls:20}") int maxConcurrentCalls,
      @Value("${bank.bulkhead.max-wait-duration:500ms}") Duration maxWaitDuration) {
    BulkheadConfig config = BulkheadConfig.custom()
        .maxConcurrentCalls(maxConcurrentCalls)
        .maxWaitDuration(maxWaitDuration)
        .build();
    return Bulkhead.of("bank", config);
  }

  @Bean
  public BulkheadRegistry merchantBulkheads(
      @Value("${gateway.merchant-max-concurrent-payments:10}") int maxConcurrentPayments) {
    return BulkheadRegistry.of(BulkheadConfig.custom()
        .maxConcurrentCalls(maxConcurrentPayments)
        .maxWaitDuration(Duration.ZERO)
        .build());
  }
}
