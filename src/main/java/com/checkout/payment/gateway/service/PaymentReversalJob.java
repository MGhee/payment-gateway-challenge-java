package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.configuration.BankProperties;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.AcquiringBankException;
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

  private final BankClient bankClient;
  private final PaymentStore paymentsRepository;
  private final MeterRegistry meterRegistry;
  private final Clock clock;
  private final int maxAttempts;
  private final Duration retryBaseDelay;

  public PaymentReversalJob(BankClient bankClient, PaymentStore paymentsRepository,
      MeterRegistry meterRegistry, BankProperties bank, Clock clock) {
    this.bankClient = bankClient;
    this.paymentsRepository = paymentsRepository;
    this.meterRegistry = meterRegistry;
    this.clock = clock;
    this.maxAttempts = bank.reversalMaxAttempts();
    this.retryBaseDelay = bank.reversalRetryBaseDelay();
  }

  @Scheduled(fixedDelayString = "${bank.reversal-retry-interval}",
      initialDelayString = "${bank.reversal-retry-interval}")
  public void reversePendingPayments() {
    paymentsRepository.findDueReversals(clock.instant()).forEach(payment -> {
      try {
        bankClient.reverse(payment.id());
      } catch (AcquiringBankException e) {
        recordFailure(payment, e);
        return;
      }
      paymentsRepository.save(payment.withStatus(PaymentStatus.DECLINED));
      meterRegistry.counter("payments.reversals", "outcome", "reversed").increment();
      LOG.info("Payment {} Declined: authorization reversed after unknown bank outcome",
          payment.id());
    });
  }

  private void recordFailure(Payment payment, AcquiringBankException e) {
    int attempts = payment.reversalAttempts() + 1;
    if (attempts < maxAttempts) {
      long multiplier = 1L << Math.min(attempts - 1, 20);
      Instant retryAt = clock.instant().plus(retryBaseDelay.multipliedBy(multiplier));
      paymentsRepository.save(payment.withReversalFailure(attempts, retryAt));
      LOG.warn("Reversal of payment {} failed (attempt {}/{}), retry scheduled at {}: {}",
          payment.id(), attempts, maxAttempts, retryAt, e.getMessage());
      return;
    }
    paymentsRepository.save(payment.withReversalFailure(attempts, null));
    meterRegistry.counter("payments.reversals", "outcome", "failed").increment();
    LOG.error("Payment {} left Pending after {} reversal attempts: manual reconciliation required",
        payment.id(), attempts, e);
  }
}
