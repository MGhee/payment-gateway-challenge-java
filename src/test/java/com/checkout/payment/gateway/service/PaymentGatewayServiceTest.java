package com.checkout.payment.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.client.BankPaymentResponse;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.AcquiringBankException;
import com.checkout.payment.gateway.exception.BankOutcomeUnknownException;
import com.checkout.payment.gateway.exception.IdempotencyConflictException;
import com.checkout.payment.gateway.exception.IdempotencyKeyReusedException;
import com.checkout.payment.gateway.exception.PaymentNotFoundException;
import com.checkout.payment.gateway.model.Payment;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.repository.InMemoryPaymentStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PaymentGatewayServiceTest {

  private static final String KEY = "5f3c1a9e-0b2d-4c6e-8f1a-3b5d7e9f1a2c";

  private final BankClient bankClient = mock(BankClient.class);
  private final InMemoryPaymentStore repository = new InMemoryPaymentStore();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final PaymentGatewayService service =
      new PaymentGatewayService(repository, bankClient, meterRegistry);
  private final String merchantId = "test-merchant";

  private final PostPaymentRequest request =
      new PostPaymentRequest("4000000000000123", 4, 2030, "USD", 1050, "123");

  @Test
  void authorizedPaymentIsStoredWithOnlyTheLastFourCardDigits() {
    when(bankClient.authorize(any(), eq(request)))
        .thenReturn(new BankPaymentResponse(true, "auth-code"));

    Payment payment = processPayment(request, null);

    verify(bankClient).authorize(payment.id(), request);
    assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
    assertThat(payment.cardNumberLastFour()).isEqualTo("0123");
    assertThat(payment.expiryMonth()).isEqualTo(4);
    assertThat(payment.expiryYear()).isEqualTo(2030);
    assertThat(payment.currency()).isEqualTo("USD");
    assertThat(payment.amount()).isEqualTo(1050);
    assertThat(getPayment(payment.id())).isEqualTo(payment);
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

    Payment payment = processPayment(request, null);

    assertThat(getPayment(payment.id()).status()).isEqualTo(PaymentStatus.AUTHORIZED);
  }

  @Test
  void declinedPaymentIsStored() {
    when(bankClient.authorize(any(), eq(request))).thenReturn(new BankPaymentResponse(false, ""));

    Payment payment = processPayment(request, null);

    assertThat(payment.status()).isEqualTo(PaymentStatus.DECLINED);
    assertThat(getPayment(payment.id())).isEqualTo(payment);
    assertThat(processedCount("Declined")).isEqualTo(1);
  }

  @Test
  void nothingIsStoredWhenTheBankDefinitelyFailed() {
    when(bankClient.authorize(any(), eq(request)))
        .thenThrow(new AcquiringBankException("Service Unavailable"));

    assertThatThrownBy(() -> processPayment(request, null))
        .isInstanceOf(AcquiringBankException.class);

    ArgumentCaptor<UUID> reference = ArgumentCaptor.forClass(UUID.class);
    verify(bankClient).authorize(reference.capture(), eq(request));
    assertThat(repository.get(reference.getValue())).isEmpty();
  }

  @Test
  void unknownBankOutcomeLeavesThePaymentPendingAndSchedulesAReversal() {
    when(bankClient.authorize(any(), eq(request)))
        .thenThrow(new BankOutcomeUnknownException("Read timed out"));

    Payment payment = processPayment(request, null);

    assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(getPayment(payment.id())).isEqualTo(payment);
    assertThat(payment.nextReversalAt()).isNotNull();
    assertThat(processedCount("Pending")).isEqualTo(1);
  }

  @Test
  void retryWithTheSameKeyReturnsTheOriginalPaymentWithoutChargingAgain() {
    when(bankClient.authorize(any(), eq(request)))
        .thenReturn(new BankPaymentResponse(true, "auth-code"));

    Payment original = processPayment(request, KEY);
    Payment retry = processPayment(request, KEY);

    assertThat(retry).isEqualTo(original);
    verify(bankClient, times(1)).authorize(any(), any());
  }

  @Test
  void idempotencyKeysAreScopedToTheMerchant() {
    when(bankClient.authorize(any(), eq(request)))
        .thenReturn(new BankPaymentResponse(true, "auth-code"));

    Payment firstMerchant = service.processPayment(request, KEY, "merchant-one");
    Payment secondMerchant = service.processPayment(request, KEY, "merchant-two");

    assertThat(firstMerchant.id()).isNotEqualTo(secondMerchant.id());
    assertThat(secondMerchant.merchantId()).isEqualTo("merchant-two");
    verify(bankClient, times(2)).authorize(any(), any());
  }

  @Test
  void retryAfterAnUnknownOutcomeReturnsThePendingPayment() {
    when(bankClient.authorize(any(), eq(request)))
        .thenThrow(new BankOutcomeUnknownException("Read timed out"));

    Payment original = processPayment(request, KEY);
    Payment retry = processPayment(request, KEY);

    assertThat(retry).isEqualTo(original);
    assertThat(retry.status()).isEqualTo(PaymentStatus.PENDING);
    verify(bankClient, times(1)).authorize(any(), any());
  }

  @Test
  void keyIsReleasedWhenTheBankDefinitelyFailedSoTheRetryIsProcessed() {
    when(bankClient.authorize(any(), eq(request)))
        .thenThrow(new AcquiringBankException("Service Unavailable"))
        .thenReturn(new BankPaymentResponse(true, "auth-code"));

    assertThatThrownBy(() -> processPayment(request, KEY))
        .isInstanceOf(AcquiringBankException.class);
    Payment retry = processPayment(request, KEY);

    assertThat(retry.status()).isEqualTo(PaymentStatus.AUTHORIZED);
    verify(bankClient, times(2)).authorize(any(), any());
  }

  @Test
  void duplicateWhileTheOriginalIsInProgressIsAConflict() {
    when(bankClient.authorize(any(), eq(request))).thenAnswer(call -> {
      assertThatThrownBy(() -> processPayment(request, KEY))
          .isInstanceOf(IdempotencyConflictException.class);
      return new BankPaymentResponse(true, "auth-code");
    });

    Payment original = processPayment(request, KEY);

    assertThat(original.status()).isEqualTo(PaymentStatus.AUTHORIZED);
    verify(bankClient, times(1)).authorize(any(), any());
  }

  @Test
  void keyReusedForADifferentPaymentIsRejected() {
    when(bankClient.authorize(any(), any())).thenReturn(new BankPaymentResponse(true, "auth-code"));
    processPayment(request, KEY);

    PostPaymentRequest differentAmount =
        new PostPaymentRequest("4000000000000123", 4, 2030, "USD", 9999, "123");

    assertThatThrownBy(() -> processPayment(differentAmount, KEY))
        .isInstanceOf(IdempotencyKeyReusedException.class);
    verify(bankClient, times(1)).authorize(any(), any());
  }

  @Test
  void unknownPaymentIsNotFound() {
    assertThatThrownBy(() -> getPayment(UUID.randomUUID()))
        .isInstanceOf(PaymentNotFoundException.class);
  }

  private Payment processPayment(PostPaymentRequest paymentRequest, String idempotencyKey) {
    return service.processPayment(paymentRequest, idempotencyKey, merchantId);
  }

  private Payment getPayment(UUID id) {
    return service.getPayment(id, merchantId);
  }

  private double processedCount(String status) {
    return meterRegistry.counter("payments.processed", "status", status).count();
  }
}
