package com.checkout.payment.gateway.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.checkout.payment.gateway.exception.AcquiringBankException;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class BankClientTest {

  private final RestTemplate restTemplate = new RestTemplateBuilder().rootUri("http://bank").build();
  private final MockRestServiceServer bank = MockRestServiceServer.bindTo(restTemplate).build();
  private final BankClient bankClient = new BankClient(restTemplate);

  private final PostPaymentRequest request =
      new PostPaymentRequest("2222405343248877", 4, 2030, "GBP", 100, "123");

  @Test
  void sendsThePaymentInTheBankFormat() {
    bank.expect(requestTo("http://bank/payments"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().json("""
            {"card_number": "2222405343248877", "expiry_date": "04/2030",
             "currency": "GBP", "amount": 100, "cvv": "123"}
            """, true))
        .andRespond(withSuccess("""
            {"authorized": true, "authorization_code": "0bb07405-6d44-4b50-a14f-7ae0beff13ad"}
            """, MediaType.APPLICATION_JSON));

    BankPaymentResponse response = bankClient.authorize(request);

    bank.verify();
    assertThat(response.authorized()).isTrue();
    assertThat(response.authorizationCode()).isEqualTo("0bb07405-6d44-4b50-a14f-7ae0beff13ad");
  }

  @Test
  void returnsUnauthorizedWhenTheBankDeclines() {
    bank.expect(requestTo("http://bank/payments"))
        .andRespond(withSuccess("""
            {"authorized": false, "authorization_code": ""}
            """, MediaType.APPLICATION_JSON));

    assertThat(bankClient.authorize(request).authorized()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 500, 503})
  void bankErrorResponseIsReportedAsBankFailure(int status) {
    bank.expect(requestTo("http://bank/payments"))
        .andRespond(withStatus(HttpStatusCode.valueOf(status)));

    assertThatThrownBy(() -> bankClient.authorize(request))
        .isInstanceOf(AcquiringBankException.class);
  }

  @Test
  void bankTimeoutIsReportedAsBankFailure() {
    bank.expect(requestTo("http://bank/payments"))
        .andRespond(req -> {
          throw new SocketTimeoutException("Read timed out");
        });

    assertThatThrownBy(() -> bankClient.authorize(request))
        .isInstanceOf(AcquiringBankException.class);
  }
}
