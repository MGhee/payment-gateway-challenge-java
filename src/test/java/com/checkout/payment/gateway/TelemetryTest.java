package com.checkout.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.logback.LogbackLoggingSystem;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(properties = {"bank.reversal-retry-interval=PT1H",
    "gateway.admin-api-key=test-admin-secret", "bank.url=http://localhost:8080"})
@AutoConfigureMockMvc
@AutoConfigureObservability(metrics = false)
@ExtendWith(OutputCaptureExtension.class)
class TelemetryTest {

  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  @Autowired
  private MockMvc mvc;
  @Autowired
  private ObjectMapper objectMapper;
  @Autowired
  private RestTemplate bankRestTemplate;
  @Autowired
  private ConfigurableEnvironment environment;

  @Test
  void incomingTraceIsForwardedToTheBankAndAddedToTheLogContext() throws Exception {
    AtomicReference<String> loggedTraceId = new AtomicReference<>();
    MockRestServiceServer bank = MockRestServiceServer.bindTo(bankRestTemplate).build();
    bank.expect(requestTo("http://localhost:8080/payments"))
        .andExpect(header("traceparent", startsWith("00-" + TRACE_ID + "-")))
        .andRespond(request -> {
          loggedTraceId.set(MDC.get("traceId"));
          return withSuccess("""
              {"authorized": true, "authorization_code": "auth-code"}
              """, MediaType.APPLICATION_JSON).createResponse(request);
        });

    mvc.perform(post("/v1/payments")
            .header("X-API-Key", provisionMerchant())
            .header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"card_number": "2222405343248877", "expiry_month": 4, "expiry_year": %d,
                 "currency": "GBP", "amount": 100, "cvv": "123"}
                """.formatted(Year.now(ZoneOffset.UTC).getValue() + 1)))
        .andExpect(status().isCreated());

    bank.verify();
    assertThat(loggedTraceId).hasValue(TRACE_ID);
  }

  @Test
  void jsonLogsProfileWritesOneJsonObjectPerLine(CapturedOutput output) throws Exception {
    // Logback is configured once per JVM, so switch to JSON explicitly and restore afterwards
    LogbackLoggingSystem logging = new LogbackLoggingSystem(getClass().getClassLoader());
    MockEnvironment jsonLogs = new MockEnvironment();
    jsonLogs.setActiveProfiles("json-logs");
    try {
      reinitialize(logging, jsonLogs);
      MDC.put("requestId", "json-test");
      LoggerFactory.getLogger(TelemetryTest.class).info("structured log line");
    } finally {
      MDC.remove("requestId");
      reinitialize(logging, environment);
    }

    String line = output.getOut().lines()
        .filter(candidate -> candidate.contains("structured log line"))
        .findFirst().orElseThrow();
    JsonNode log = objectMapper.readTree(line);
    assertThat(log.path("message").asText()).isEqualTo("structured log line");
    assertThat(log.path("level").asText()).isEqualTo("INFO");
    assertThat(log.path("logger_name").asText()).isEqualTo(TelemetryTest.class.getName());
    assertThat(log.path("service").asText()).isEqualTo("payment-gateway");
    assertThat(log.path("requestId").asText()).isEqualTo("json-test");
  }

  private static void reinitialize(LogbackLoggingSystem logging,
      ConfigurableEnvironment environment) {
    logging.cleanUp();
    logging.beforeInitialize();
    logging.initialize(new LoggingInitializationContext(environment), null, null);
  }

  private String provisionMerchant() throws Exception {
    String body = mvc.perform(post("/admin/merchants/telemetry-merchant/api-keys")
            .header("X-Gateway-Admin-Key", "test-admin-secret"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return JsonPath.read(body, "$.api_key");
  }
}
