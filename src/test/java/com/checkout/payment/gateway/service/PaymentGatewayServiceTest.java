package com.checkout.payment.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.client.BankPaymentResponse;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.AcquiringBankException;
import com.checkout.payment.gateway.exception.PaymentNotFoundException;
import com.checkout.payment.gateway.model.Payment;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentGatewayServiceTest {

  private final BankClient bankClient = mock(BankClient.class);
  private final PaymentsRepository repository = spy(new PaymentsRepository());
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final PaymentGatewayService service =
      new PaymentGatewayService(repository, bankClient, meterRegistry);

  private final PostPaymentRequest request =
      new PostPaymentRequest("4000000000000123", 4, 2030, "USD", 1050, "123");

  @Test
  void authorizedPaymentIsStoredWithOnlyTheLastFourCardDigits() {
    when(bankClient.authorize(request)).thenReturn(new BankPaymentResponse(true, "auth-code"));

    Payment payment = service.processPayment(request);

    assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
    assertThat(payment.cardNumberLastFour()).isEqualTo("0123");
    assertThat(payment.expiryMonth()).isEqualTo(4);
    assertThat(payment.expiryYear()).isEqualTo(2030);
    assertThat(payment.currency()).isEqualTo("USD");
    assertThat(payment.amount()).isEqualTo(1050);
    assertThat(service.getPayment(payment.id())).isEqualTo(payment);
    assertThat(processedCount("Authorized")).isEqualTo(1);
  }

  @Test
  void declinedPaymentIsStored() {
    when(bankClient.authorize(request)).thenReturn(new BankPaymentResponse(false, ""));

    Payment payment = service.processPayment(request);

    assertThat(payment.status()).isEqualTo(PaymentStatus.DECLINED);
    assertThat(service.getPayment(payment.id())).isEqualTo(payment);
    assertThat(processedCount("Declined")).isEqualTo(1);
  }

  @Test
  void nothingIsStoredWhenTheBankFails() {
    when(bankClient.authorize(request)).thenThrow(new AcquiringBankException("Service Unavailable"));

    assertThatThrownBy(() -> service.processPayment(request))
        .isInstanceOf(AcquiringBankException.class);
    verify(repository, never()).add(any());
  }

  @Test
  void unknownPaymentIsNotFound() {
    assertThatThrownBy(() -> service.getPayment(UUID.randomUUID()))
        .isInstanceOf(PaymentNotFoundException.class);
  }

  private double processedCount(String status) {
    return meterRegistry.counter("payments.processed", "status", status).count();
  }
}
