package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.configuration.BankProperties;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.AcquiringBankException;
import com.checkout.payment.gateway.exception.BankCallRejectedException;
import com.checkout.payment.gateway.model.Payment;
import com.checkout.payment.gateway.repository.PaymentStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Reverses authorizations whose outcome is unknown; reversed payments become Declined
@Component
public class PaymentReversalJob {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentReversalJob.class);
  private static final int BATCH_SIZE = 10;
  private static final Duration LEASE_MARGIN = Duration.ofSeconds(30);

  private final BankClient bankClient;
  private final PaymentStore paymentsRepository;
  private final MeterRegistry meterRegistry;
  private final Clock clock;
  private final int maxAttempts;
  private final Duration retryBaseDelay;
  private final Duration lease;

  public PaymentReversalJob(BankClient bankClient, PaymentStore paymentsRepository,
      MeterRegistry meterRegistry, BankProperties bank, Clock clock) {
    this.bankClient = bankClient;
    this.paymentsRepository = paymentsRepository;
    this.meterRegistry = meterRegistry;
    this.clock = clock;
    this.maxAttempts = bank.reversalMaxAttempts();
    this.retryBaseDelay = bank.reversalRetryBaseDelay();
    // Long enough for every bank call in a batch; a crashed worker's claims expire after this
    this.lease = bank.connectTimeout().plus(bank.readTimeout()).multipliedBy(BATCH_SIZE)
        .plus(LEASE_MARGIN);
  }

  @Scheduled(fixedDelayString = "${bank.reversal-retry-interval}",
      initialDelayString = "${bank.reversal-retry-interval}")
  public void reversePendingPayments() {
    Instant now = clock.instant();
    paymentsRepository.claimDueReversals(now, now.plus(lease), BATCH_SIZE).forEach(this::reverse);
  }

  private void reverse(Payment payment) {
    try {
      bankClient.reverse(payment.id());
    } catch (BankCallRejectedException e) {
      defer(payment);
      return;
    } catch (AcquiringBankException e) {
      recordFailure(payment, e);
      return;
    }
    if (!record(payment, payment.withStatus(PaymentStatus.DECLINED))) {
      return;
    }
    meterRegistry.counter("payments.reversals", "outcome", "reversed").increment();
    LOG.info("Payment {} Declined: authorization reversed after unknown bank outcome",
        payment.id());
  }

  // The bank never saw this attempt, so it does not count towards manual reconciliation
  private void defer(Payment payment) {
    Instant retryAt = clock.instant().plus(retryBaseDelay);
    if (record(payment, payment.withNextReversalAt(retryAt))) {
      meterRegistry.counter("payments.reversals", "outcome", "deferred").increment();
      LOG.warn("Reversal of payment {} deferred to {}: bank calls are being rejected locally",
          payment.id(), retryAt);
    }
  }

  private void recordFailure(Payment payment, AcquiringBankException e) {
    int attempts = payment.reversalAttempts() + 1;
    if (attempts < maxAttempts) {
      long multiplier = 1L << Math.min(attempts - 1, 20);
      Instant retryAt = clock.instant().plus(retryBaseDelay.multipliedBy(multiplier));
      if (record(payment, payment.withReversalFailure(attempts, retryAt))) {
        LOG.warn("Reversal of payment {} failed (attempt {}/{}), retry scheduled at {}: {}",
            payment.id(), attempts, maxAttempts, retryAt, e.getMessage());
      }
      return;
    }
    if (!record(payment, payment.withReversalFailure(attempts, null))) {
      return;
    }
    meterRegistry.counter("payments.reversals", "outcome", "failed").increment();
    LOG.error("Payment {} left Pending after {} reversal attempts: manual reconciliation required",
        payment.id(), attempts, e);
  }

  private boolean record(Payment claimed, Payment next) {
    if (paymentsRepository.transition(claimed, next)) {
      return true;
    }
    LOG.warn("Payment {} was updated by another worker during its reversal; keeping that update",
        claimed.id());
    return false;
  }
}
