package com.checkout.payment.gateway.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.client.BankPaymentResponse;
import com.checkout.payment.gateway.exception.AcquiringBankException;
import com.checkout.payment.gateway.exception.BankOutcomeUnknownException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = "bank.reversal-retry-interval=PT1H")
@AutoConfigureMockMvc
class PaymentGatewayControllerTest {

  private static final int NEXT_YEAR = Year.now(ZoneOffset.UTC).getValue() + 1;

  @Autowired
  private MockMvc mvc;
  @Autowired
  private ObjectMapper objectMapper;
  @MockBean
  private BankClient bankClient;

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
    mvc.perform(get(created.getResponse().getHeader("Location")).header("X-API-Key", "test-secret"))
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

    mvc.perform(get(accepted.getResponse().getHeader("Location")).header("X-API-Key", "test-secret"))
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
      .header("X-API-Key", "test-secret")
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

  @Test
  void fractionalAmountIsRejectedRatherThanTruncated() throws Exception {
    Map<String, Object> request = validRequest();
    request.put("amount", 10.5);

    postPayment(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("Rejected"))
        .andExpect(jsonPath("$.message").value("Malformed request body"));
    verifyNoInteractions(bankClient);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not json", "{\"expiry_month\": \"April\"}"})
  void malformedBodyIsRejectedWithoutCallingTheBank(String body) throws Exception {
    mvc.perform(post("/payments")
      .header("X-API-Key", "test-secret")
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
            .value("X-API-Key"));
  }

  @Test
  void merchantCannotRetrieveAnotherMerchantsPayment() throws Exception {
    when(bankClient.authorize(any(), any())).thenReturn(new BankPaymentResponse(true, "auth-code"));
    MvcResult created = postPayment(validRequest())
        .andExpect(status().isCreated())
        .andReturn();

    mvc.perform(get("/payments/" + JsonPath.read(created.getResponse().getContentAsString(), "$.id"))
        .header("X-API-Key", "other-secret"))
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

  @ParameterizedTest
  @ValueSource(strings = {"6f1c2b9e-4d7a-4a5b-9c3e-1f2a3b4c5d6e", "not-a-uuid"})
  void unknownPaymentReturnsNotFound(String id) throws Exception {
    mvc.perform(get("/payments/" + id).header("X-API-Key", "test-secret"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Payment not found"));
  }

  @Test
  void requestIdIsEchoedBack() throws Exception {
    mvc.perform(get("/payments/" + UUID.randomUUID())
      .header("X-API-Key", "test-secret")
      .header("X-Request-Id", "merchant-req-42"))
        .andExpect(header().string("X-Request-Id", "merchant-req-42"));
  }

  @Test
  void unsafeRequestIdIsReplaced() throws Exception {
    mvc.perform(get("/payments/" + UUID.randomUUID())
      .header("X-API-Key", "test-secret")
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
        .header("X-API-Key", "test-secret")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(request)));
  }

  private ResultActions postPayment(Map<String, Object> request, String idempotencyKey)
      throws Exception {
    return mvc.perform(post("/payments")
      .header("X-API-Key", "test-secret")
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(request)));
  }
}
