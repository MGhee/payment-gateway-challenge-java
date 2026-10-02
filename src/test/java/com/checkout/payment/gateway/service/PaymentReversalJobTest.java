package com.checkout.payment.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.configuration.BankProperties;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.AcquiringBankException;
import com.checkout.payment.gateway.model.Payment;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaymentReversalJobTest {

  private static final int MAX_ATTEMPTS = 3;

  private final BankClient bankClient = mock(BankClient.class);
  private final PaymentsRepository repository = new PaymentsRepository();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final PaymentReversalJob job = new PaymentReversalJob(bankClient, repository,
      meterRegistry, new BankProperties("http://bank", Duration.ZERO, Duration.ZERO, MAX_ATTEMPTS));

  private final Payment pending = Payment.pending(
      new PostPaymentRequest("2222405343248877", 4, 2030, "GBP", 100, "123"), null);

  @BeforeEach
  void scheduleReversalOfPendingPayment() {
    repository.save(pending);
    job.schedule(pending.id());
  }

  @Test
  void reversedPaymentBecomesDeclinedAndIsNotReversedAgain() {
    job.reversePendingPayments();
    job.reversePendingPayments();

    verify(bankClient).reverse(pending.id());
    verifyNoMoreInteractions(bankClient);
    assertThat(status()).isEqualTo(PaymentStatus.DECLINED);
    assertThat(reversals("reversed")).isEqualTo(1);
  }

  @Test
  void failedReversalIsRetriedOnTheNextRun() {
    doThrow(new AcquiringBankException("Service Unavailable")).doNothing()
        .when(bankClient).reverse(pending.id());

    job.reversePendingPayments();
    assertThat(status()).isEqualTo(PaymentStatus.PENDING);

    job.reversePendingPayments();
    assertThat(status()).isEqualTo(PaymentStatus.DECLINED);
    verify(bankClient, times(2)).reverse(pending.id());
  }

  @Test
  void paymentIsLeftPendingForManualReconciliationAfterMaxAttempts() {
    doThrow(new AcquiringBankException("Service Unavailable"))
        .when(bankClient).reverse(pending.id());

    for (int run = 0; run < MAX_ATTEMPTS + 2; run++) {
      job.reversePendingPayments();
    }

    verify(bankClient, times(MAX_ATTEMPTS)).reverse(pending.id());
    assertThat(status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(reversals("failed")).isEqualTo(1);
  }

  private PaymentStatus status() {
    return repository.get(pending.id()).orElseThrow().status();
  }

  private double reversals(String outcome) {
    return meterRegistry.counter("payments.reversals", "outcome", outcome).count();
  }
}
