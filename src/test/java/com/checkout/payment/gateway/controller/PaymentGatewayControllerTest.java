package com.checkout.payment.gateway.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.client.BankPaymentResponse;
import com.checkout.payment.gateway.exception.AcquiringBankException;
import com.checkout.payment.gateway.exception.BankOutcomeUnknownException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

// Inline properties outrank environment variables such as GATEWAY_ADMIN_API_KEY exported for Compose
@SpringBootTest(properties = {"bank.reversal-retry-interval=PT1H",
    "gateway.admin-api-key=test-admin-secret"})
@AutoConfigureMockMvc
class PaymentGatewayControllerTest {

  private static final int NEXT_YEAR = Year.now(ZoneOffset.UTC).getValue() + 1;

  @Autowired
  private MockMvc mvc;
  @Autowired
  private ObjectMapper objectMapper;
  @Autowired
  private JdbcTemplate jdbcTemplate;
  @MockBean
  private BankClient bankClient;
  private String merchantApiKey;

  @BeforeEach
  void provisionTestMerchant() throws Exception {
    merchantApiKey = provisionMerchant("test-merchant");
  }

  private String provisionMerchant(String merchantId) throws Exception {
    String body = mvc.perform(post("/admin/merchants/" + merchantId + "/api-keys")
            .header("X-Gateway-Admin-Key", "test-admin-secret"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return JsonPath.read(body, "$.api_key");
  }

  @Test
  void authorizedPaymentIsCreatedAndCanBeRetrieved() throws Exception {
    when(bankClient.authorize(any(), any())).thenReturn(new BankPaymentResponse(true, "auth-code"));

    MvcResult created = postPayment(validRequest())
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", matchesPattern("/payments/[0-9a-f-]{36}")))
        .andExpect(jsonPath("$.status").value("Authorized"))
        .andExpect(jsonPath("$.card_number_last_four").value("8877"))
        .andExpect(jsonPath("$.expiry_month").value(4))
        .andExpect(jsonPath("$.expiry_year").value(NEXT_YEAR))
        .andExpect(jsonPath("$.currency").value("GBP"))
        .andExpect(jsonPath("$.amount").value(100))
        .andExpect(jsonPath("$.card_number").doesNotExist())
        .andExpect(jsonPath("$.cvv").doesNotExist())
        .andReturn();

    String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    mvc.perform(get(created.getResponse().getHeader("Location")).header("X-API-Key", merchantApiKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.status").value("Authorized"))
        .andExpect(jsonPath("$.card_number_last_four").value("8877"))
        .andExpect(jsonPath("$.expiry_month").value(4))
        .andExpect(jsonPath("$.expiry_year").value(NEXT_YEAR))
        .andExpect(jsonPath("$.currency").value("GBP"))
        .andExpect(jsonPath("$.amount").value(100));
  }

  @Test
  void declinedPaymentIsCreatedWithDeclinedStatus() throws Exception {
    when(bankClient.authorize(any(), any())).thenReturn(new BankPaymentResponse(false, ""));

    postPayment(validRequest())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("Declined"));
  }

  @Test
  void unknownBankOutcomeIsAcceptedAsPending() throws Exception {
    when(bankClient.authorize(any(), any()))
        .thenThrow(new BankOutcomeUnknownException("Read timed out"));

    MvcResult accepted = postPayment(validRequest())
        .andExpect(status().isAccepted())
        .andExpect(header().string("Location", matchesPattern("/payments/[0-9a-f-]{36}")))
        .andExpect(jsonPath("$.status").value("Pending"))
        .andReturn();

    mvc.perform(get(accepted.getResponse().getHeader("Location")).header("X-API-Key", merchantApiKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("Pending"));
  }

  @ParameterizedTest(name = "{0} = {1}")
  @MethodSource("boundaryValues")
  void boundaryValuesAreAccepted(String field, Object value) throws Exception {
    when(bankClient.authorize(any(), any())).thenReturn(new BankPaymentResponse(true, "auth-code"));
    Map<String, Object> request = validRequest();
    request.put(field, value);

    postPayment(request).andExpect(status().isCreated());
  }

  static Stream<Arguments> boundaryValues() {
    return Stream.of(
        Arguments.of("card_number", "22224053432487"),
        Arguments.of("card_number", "2222405343248877123"),
        Arguments.of("expiry_month", 1),
        Arguments.of("expiry_month", 12),
        Arguments.of("currency", "USD"),
        Arguments.of("currency", "EUR"),
        Arguments.of("amount", 1),
        Arguments.of("cvv", "1234"));
  }

  @ParameterizedTest(name = "{0} = {1}")
  @MethodSource("invalidValues")
  void invalidPaymentIsRejectedWithoutCallingTheBank(String field, Object value, String error)
      throws Exception {
    Map<String, Object> request = validRequest();
    request.put(field, value);

    postPayment(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("Rejected"))
        .andExpect(jsonPath("$.errors", contains(error)));
    verifyNoInteractions(bankClient);
  }

  static Stream<Arguments> invalidValues() {
    return Stream.of(
        Arguments.of("card_number", null, "card_number must not be null"),
        Arguments.of("card_number", "2222405343248", "card_number must be 14-19 digits"),
        Arguments.of("card_number", "22224053432488771234", "card_number must be 14-19 digits"),
        Arguments.of("card_number", "2222-4053-4324-8877", "card_number must be 14-19 digits"),
        Arguments.of("expiry_month", null, "expiry_month must not be null"),
        Arguments.of("expiry_month", 0, "expiry_month must be between 1 and 12"),
        Arguments.of("expiry_month", 13, "expiry_month must be between 1 and 12"),
        Arguments.of("expiry_year", null, "expiry_year must not be null"),
        Arguments.of("expiry_year", 2020, "card expiry date must be in the future"),
        Arguments.of("currency", null, "currency must not be null"),
        Arguments.of("currency", "JPY", "currency must be one of USD, GBP, EUR"),
        Arguments.of("currency", "gbp", "currency must be one of USD, GBP, EUR"),
        Arguments.of("amount", null, "amount must not be null"),
        Arguments.of("amount", 0, "amount must be greater than 0"),
        Arguments.of("amount", -100, "amount must be greater than 0"),
        Arguments.of("cvv", null, "cvv must not be null"),
        Arguments.of("cvv", "12", "cvv must be 3-4 digits"),
        Arguments.of("cvv", "12345", "cvv must be 3-4 digits"),
        Arguments.of("cvv", "12a", "cvv must be 3-4 digits"));
  }

  @Test
  void everyMissingFieldIsReported() throws Exception {
    mvc.perform(post("/payments")
        .header("X-API-Key", merchantApiKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("Rejected"))
        .andExpect(jsonPath("$.errors", containsInAnyOrder(
            "amount must not be null",
            "card_number must not be null",
            "currency must not be null",
            "cvv must not be null",
            "expiry_month must not be null",
            "expiry_year must not be null")));
    verifyNoInteractions(bankClient);
  }

  @ParameterizedTest
  @MethodSource("nonIntegerAmounts")
  void nonIntegerAmountIsRejectedRatherThanCoerced(Object amount) throws Exception {
    Map<String, Object> request = validRequest();
    request.put("amount", amount);

    postPayment(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("Rejected"))
        .andExpect(jsonPath("$.message").value("Malformed request body"));
    verifyNoInteractions(bankClient);
  }

  static Stream<Object> nonIntegerAmounts() {
    return Stream.of(10.5, "1050");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not json", "{\"expiry_month\": \"April\"}"})
  void malformedBodyIsRejectedWithoutCallingTheBank(String body) throws Exception {
    mvc.perform(post("/payments")
        .header("X-API-Key", merchantApiKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("Rejected"))
        .andExpect(jsonPath("$.message").value("Malformed request body"));
    verifyNoInteractions(bankClient);
  }

  @Test
  void bankFailureReturnsBadGateway() throws Exception {
    when(bankClient.authorize(any(), any()))
        .thenThrow(new AcquiringBankException("Service Unavailable"));

    postPayment(validRequest())
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.message").value("Acquiring bank unavailable, please retry later"));
  }

  @Test
  void paymentRoutesRequireMerchantAuthentication() throws Exception {
    mvc.perform(post("/payments")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(validRequest())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Authentication required"));
    verifyNoInteractions(bankClient);
  }

  @Test
  void openApiDocumentsMerchantApiKeyAuthentication() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.securitySchemes.MerchantApiKey.name")
            .value("X-API-Key"))
        .andExpect(jsonPath("$.components.securitySchemes.GatewayAdminKey.name")
            .value("X-Gateway-Admin-Key"));
  }

  @Test
  void merchantKeysAreStoredAsHashes() {
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM merchant_api_keys "
        + "WHERE key_hash = ?", Long.class, merchantApiKey)).isZero();
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM merchant_api_keys "
        + "WHERE key_prefix = ?", Long.class, merchantApiKey.substring(0, 12))).isEqualTo(1);
  }

  @Test
  void credentialAdministrationRequiresTheAdminKey() throws Exception {
    mvc.perform(post("/admin/merchants/new-merchant/api-keys"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type")
            .value("urn:payment-gateway:problem:admin-authentication-required"));
  }

  @Test
  void versionedApiUsesVersionedLocationAndProblemDetails() throws Exception {
    when(bankClient.authorize(any(), any())).thenReturn(new BankPaymentResponse(true, "auth-code"));
    MvcResult created = postVersionedPayment(validRequest())
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", matchesPattern("/v1/payments/[0-9a-f-]{36}")))
        .andReturn();
    String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    mvc.perform(get("/v1/payments/" + id).header("X-API-Key", merchantApiKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id));

    mvc.perform(post("/v1/payments")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(validRequest())))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(401));

    Map<String, Object> invalidRequest = validRequest();
    invalidRequest.put("amount", 0);
    postVersionedPayment(invalidRequest)
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:payment-gateway:problem:invalid-payment-request"))
        .andExpect(jsonPath("$.title").value("Bad Request"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.payment_status").value("Rejected"))
        .andExpect(jsonPath("$.detail").value("Invalid payment request"))
        .andExpect(jsonPath("$.errors[0]").value("amount must be greater than 0"));
  }

  @Test
  void merchantCannotRetrieveAnotherMerchantsPayment() throws Exception {
    when(bankClient.authorize(any(), any())).thenReturn(new BankPaymentResponse(true, "auth-code"));
    String otherMerchantApiKey = provisionMerchant("other-merchant");
    MvcResult created = postPayment(validRequest())
        .andExpect(status().isCreated())
        .andReturn();

    mvc.perform(get("/payments/" + JsonPath.read(created.getResponse().getContentAsString(), "$.id"))
        .header("X-API-Key", otherMerchantApiKey))
        .andExpect(status().isNotFound());
  }

  @Test
  void retryWithTheSameIdempotencyKeyReturnsTheSamePayment() throws Exception {
    when(bankClient.authorize(any(), any())).thenReturn(new BankPaymentResponse(true, "auth-code"));
    String key = UUID.randomUUID().toString();

    String originalId = JsonPath.read(postPayment(validRequest(), key)
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString(), "$.id");

    postPayment(validRequest(), key)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(originalId))
        .andExpect(jsonPath("$.status").value("Authorized"));
    verify(bankClient, times(1)).authorize(any(), any());
  }

  @Test
  void idempotencyKeyReusedForADifferentPaymentIsUnprocessable() throws Exception {
    when(bankClient.authorize(any(), any())).thenReturn(new BankPaymentResponse(true, "auth-code"));
    String key = UUID.randomUUID().toString();
    postPayment(validRequest(), key).andExpect(status().isCreated());

    Map<String, Object> differentAmount = validRequest();
    differentAmount.put("amount", 9999);

    postPayment(differentAmount, key)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.message")
            .value("This Idempotency-Key was already used for a different payment"));
  }

  @Test
  void expiredIdempotencyKeyCanBeUsedForANewPayment() throws Exception {
    when(bankClient.authorize(any(), any())).thenReturn(new BankPaymentResponse(true, "auth-code"));
    String key = UUID.randomUUID().toString();
    String firstId = JsonPath.read(postPayment(validRequest(), key)
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString(), "$.id");
    jdbcTemplate.update("UPDATE payment_idempotency_keys SET expires_at = ? "
        + "WHERE merchant_id = ? AND idempotency_key = ?",
        Timestamp.from(Instant.now().minusSeconds(1)), "test-merchant", key);

    String secondId = JsonPath.read(postPayment(validRequest(), key)
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString(), "$.id");

    assertThat(secondId).isNotEqualTo(firstId);
    assertThat(jdbcTemplate.queryForObject("SELECT idempotency_key FROM payments WHERE id = ?",
        String.class, UUID.fromString(firstId))).isNull();
    verify(bankClient, times(2)).authorize(any(), any());
  }

  @Test
  void merchantCredentialsCanBeRotatedAndRevoked() throws Exception {
    String path = "/admin/merchants/lifecycle/api-keys";
    MvcResult provisioned = mvc.perform(post(path)
        .header("X-Gateway-Admin-Key", "test-admin-secret"))
        .andExpect(status().isCreated())
        .andReturn();
    String originalKey = JsonPath.read(provisioned.getResponse().getContentAsString(), "$.api_key");
    String keyId = JsonPath.read(provisioned.getResponse().getContentAsString(), "$.key_id");

    MvcResult rotated = mvc.perform(post(path + "/" + keyId + "/rotate")
        .header("X-Gateway-Admin-Key", "test-admin-secret"))
        .andExpect(status().isOk())
        .andReturn();
    String replacementKey = JsonPath.read(rotated.getResponse().getContentAsString(), "$.api_key");
    String replacementId = JsonPath.read(rotated.getResponse().getContentAsString(), "$.key_id");

    mvc.perform(get("/payments/" + UUID.randomUUID()).header("X-API-Key", originalKey))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/payments/" + UUID.randomUUID()).header("X-API-Key", replacementKey))
        .andExpect(status().isNotFound());
    mvc.perform(delete(path + "/" + replacementId)
        .header("X-Gateway-Admin-Key", "test-admin-secret"))
        .andExpect(status().isNoContent());
    mvc.perform(get("/payments/" + UUID.randomUUID()).header("X-API-Key", replacementKey))
        .andExpect(status().isUnauthorized());
  }

  @ParameterizedTest
  @ValueSource(strings = {"6f1c2b9e-4d7a-4a5b-9c3e-1f2a3b4c5d6e", "not-a-uuid"})
  void unknownPaymentReturnsNotFound(String id) throws Exception {
    mvc.perform(get("/payments/" + id).header("X-API-Key", merchantApiKey))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Payment not found"));
  }

  @Test
  void requestIdIsEchoedBack() throws Exception {
    mvc.perform(get("/payments/" + UUID.randomUUID())
        .header("X-API-Key", merchantApiKey)
        .header("X-Request-Id", "merchant-req-42"))
        .andExpect(header().string("X-Request-Id", "merchant-req-42"));
  }

  @Test
  void unsafeRequestIdIsReplaced() throws Exception {
    mvc.perform(get("/payments/" + UUID.randomUUID())
        .header("X-API-Key", merchantApiKey)
        .header("X-Request-Id", "id\nforged log"))
        .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
  }

  private Map<String, Object> validRequest() {
    Map<String, Object> request = new HashMap<>();
    request.put("card_number", "2222405343248877");
    request.put("expiry_month", 4);
    request.put("expiry_year", NEXT_YEAR);
    request.put("currency", "GBP");
    request.put("amount", 100);
    request.put("cvv", "123");
    return request;
  }

  private ResultActions postPayment(Map<String, Object> request) throws Exception {
    return mvc.perform(post("/payments")
        .header("X-API-Key", merchantApiKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(request)));
  }

  private ResultActions postPayment(Map<String, Object> request, String idempotencyKey)
      throws Exception {
    return mvc.perform(post("/payments")
        .header("X-API-Key", merchantApiKey)
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(request)));
  }

  private ResultActions postVersionedPayment(Map<String, Object> request) throws Exception {
    return mvc.perform(post("/v1/payments")
        .header("X-API-Key", merchantApiKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(request)));
  }
}
