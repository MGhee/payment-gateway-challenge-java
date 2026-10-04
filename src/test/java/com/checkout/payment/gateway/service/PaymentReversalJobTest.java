package com.checkout.payment.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.configuration.BankProperties;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.AcquiringBankException;
import com.checkout.payment.gateway.exception.BankCallRejectedException;
import com.checkout.payment.gateway.model.Payment;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.repository.InMemoryPaymentStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaymentReversalJobTest {

  private static final int MAX_ATTEMPTS = 3;
  private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

  private final BankClient bankClient = mock(BankClient.class);
  private final InMemoryPaymentStore repository = new InMemoryPaymentStore();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final PaymentReversalJob job = new PaymentReversalJob(bankClient, repository,
        meterRegistry, new BankProperties("http://bank", Duration.ZERO, Duration.ZERO, MAX_ATTEMPTS,
            Duration.ZERO), Clock.fixed(NOW, ZoneOffset.UTC));

  private final Payment pending = Payment.pending(
      new PostPaymentRequest("2222405343248877", 4, 2030, "GBP", 100, "123"), null,
      "test-merchant");

  @BeforeEach
  void scheduleReversalOfPendingPayment() {
    repository.save(pending.withUnknownOutcome(NOW));
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
  void exponentialRetryScheduleSurvivesJobRecreation() {
    doThrow(new AcquiringBankException("Service Unavailable"),
        new AcquiringBankException("Service Unavailable")).when(bankClient).reverse(pending.id());
    PaymentReversalJob firstRun = job(Duration.ofSeconds(5), NOW);

    firstRun.reversePendingPayments();
    assertThat(repository.get(pending.id()).orElseThrow().nextReversalAt())
        .isEqualTo(NOW.plusSeconds(5));

    PaymentReversalJob restartedJob = job(Duration.ofSeconds(5), NOW.plusSeconds(5));
    restartedJob.reversePendingPayments();

    Payment retried = repository.get(pending.id()).orElseThrow();
    assertThat(retried.reversalAttempts()).isEqualTo(2);
    assertThat(retried.nextReversalAt()).isEqualTo(NOW.plusSeconds(15));
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

  @Test
  void locallyRejectedReversalIsDeferredWithoutUsingAnAttempt() {
    doThrow(new BankCallRejectedException(new IllegalStateException("circuit open")))
        .when(bankClient).reverse(pending.id());
    PaymentReversalJob deferringJob = job(Duration.ofSeconds(10), NOW);

    deferringJob.reversePendingPayments();

    Payment deferred = repository.get(pending.id()).orElseThrow();
    assertThat(deferred.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(deferred.reversalAttempts()).isZero();
    assertThat(deferred.nextReversalAt()).isEqualTo(NOW.plusSeconds(10));
    assertThat(reversals("deferred")).isEqualTo(1);
  }

  @Test
  void locallyRejectedReversalsNeverEndInManualReconciliation() {
    doThrow(new BankCallRejectedException(new IllegalStateException("bulkhead full")))
        .when(bankClient).reverse(pending.id());

    for (int run = 0; run < MAX_ATTEMPTS + 2; run++) {
      job.reversePendingPayments();
    }

    verify(bankClient, times(MAX_ATTEMPTS + 2)).reverse(pending.id());
    assertThat(repository.get(pending.id()).orElseThrow().reversalAttempts()).isZero();
    assertThat(reversals("failed")).isZero();
  }

  @Test
  void paymentBeingReversedIsNotClaimedBySecondWorker() {
    PaymentReversalJob secondWorker = job(Duration.ZERO, NOW);
    AtomicBoolean firstCall = new AtomicBoolean(true);
    doAnswer(call -> {
      if (firstCall.getAndSet(false)) {
        secondWorker.reversePendingPayments();
      }
      return null;
    }).when(bankClient).reverse(pending.id());

    job.reversePendingPayments();

    verify(bankClient, times(1)).reverse(pending.id());
    assertThat(status()).isEqualTo(PaymentStatus.DECLINED);
  }

  @Test
  void outcomeRecordedByAnotherWorkerIsNotOverwritten() {
    doAnswer(call -> {
      Payment claimed = repository.get(pending.id()).orElseThrow();
      repository.transition(claimed, claimed.withStatus(PaymentStatus.DECLINED));
      throw new AcquiringBankException("Service Unavailable");
    }).when(bankClient).reverse(pending.id());

    job.reversePendingPayments();

    assertThat(status()).isEqualTo(PaymentStatus.DECLINED);
    assertThat(repository.get(pending.id()).orElseThrow().reversalAttempts()).isZero();
  }

  private PaymentStatus status() {
    return repository.get(pending.id()).orElseThrow().status();
  }

  private PaymentReversalJob job(Duration retryBaseDelay, Instant now) {
    return new PaymentReversalJob(bankClient, repository, meterRegistry,
        new BankProperties("http://bank", Duration.ZERO, Duration.ZERO, MAX_ATTEMPTS,
            retryBaseDelay), Clock.fixed(now, ZoneOffset.UTC));
  }

  private double reversals(String outcome) {
    return meterRegistry.counter("payments.reversals", "outcome", outcome).count();
  }
}
