package com.checkout.payment.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.client.BankPaymentResponse;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.AcquiringBankException;
import com.checkout.payment.gateway.exception.BankOutcomeUnknownException;
import com.checkout.payment.gateway.exception.PaymentNotFoundException;
import com.checkout.payment.gateway.model.Payment;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PaymentGatewayServiceTest {

  private final BankClient bankClient = mock(BankClient.class);
  private final PaymentReversalJob reversalJob = mock(PaymentReversalJob.class);
  private final PaymentsRepository repository = new PaymentsRepository();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final PaymentGatewayService service =
      new PaymentGatewayService(repository, bankClient, reversalJob, meterRegistry);

  private final PostPaymentRequest request =
      new PostPaymentRequest("4000000000000123", 4, 2030, "USD", 1050, "123");

  @Test
  void authorizedPaymentIsStoredWithOnlyTheLastFourCardDigits() {
    when(bankClient.authorize(any(), eq(request)))
        .thenReturn(new BankPaymentResponse(true, "auth-code"));

    Payment payment = service.processPayment(request);

    verify(bankClient).authorize(payment.id(), request);
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
  void paymentIsStoredAsPendingBeforeTheBankIsCalled() {
    when(bankClient.authorize(any(), eq(request))).thenAnswer(call -> {
      UUID reference = call.getArgument(0);
      assertThat(repository.get(reference).orElseThrow().status())
          .isEqualTo(PaymentStatus.PENDING);
      return new BankPaymentResponse(true, "auth-code");
    });

    Payment payment = service.processPayment(request);

    assertThat(service.getPayment(payment.id()).status()).isEqualTo(PaymentStatus.AUTHORIZED);
  }

  @Test
  void declinedPaymentIsStored() {
    when(bankClient.authorize(any(), eq(request))).thenReturn(new BankPaymentResponse(false, ""));

    Payment payment = service.processPayment(request);

    assertThat(payment.status()).isEqualTo(PaymentStatus.DECLINED);
    assertThat(service.getPayment(payment.id())).isEqualTo(payment);
    assertThat(processedCount("Declined")).isEqualTo(1);
  }

  @Test
  void nothingIsStoredWhenTheBankDefinitelyFailed() {
    when(bankClient.authorize(any(), eq(request)))
        .thenThrow(new AcquiringBankException("Service Unavailable"));

    assertThatThrownBy(() -> service.processPayment(request))
        .isInstanceOf(AcquiringBankException.class);

    ArgumentCaptor<UUID> reference = ArgumentCaptor.forClass(UUID.class);
    verify(bankClient).authorize(reference.capture(), eq(request));
    assertThat(repository.get(reference.getValue())).isEmpty();
    verifyNoInteractions(reversalJob);
  }

  @Test
  void unknownBankOutcomeLeavesThePaymentPendingAndSchedulesAReversal() {
    when(bankClient.authorize(any(), eq(request)))
        .thenThrow(new BankOutcomeUnknownException("Read timed out"));

    Payment payment = service.processPayment(request);

    assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(service.getPayment(payment.id())).isEqualTo(payment);
    verify(reversalJob).schedule(payment.id());
    assertThat(processedCount("Pending")).isEqualTo(1);
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
