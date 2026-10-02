package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.configuration.BankProperties;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.AcquiringBankException;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Reverses authorizations whose outcome is unknown; reversed payments become Declined
@Component
public class PaymentReversalJob {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentReversalJob.class);

  private final Map<UUID, Integer> failedAttempts = new ConcurrentHashMap<>();
  private final BankClient bankClient;
  private final PaymentsRepository paymentsRepository;
  private final MeterRegistry meterRegistry;
  private final int maxAttempts;

  public PaymentReversalJob(BankClient bankClient, PaymentsRepository paymentsRepository,
      MeterRegistry meterRegistry, BankProperties bank) {
    this.bankClient = bankClient;
    this.paymentsRepository = paymentsRepository;
    this.meterRegistry = meterRegistry;
    this.maxAttempts = bank.reversalMaxAttempts();
  }

  public void schedule(UUID paymentId) {
    failedAttempts.put(paymentId, 0);
  }

  @Scheduled(fixedDelayString = "${bank.reversal-retry-interval}",
      initialDelayString = "${bank.reversal-retry-interval}")
  public void reversePendingPayments() {
    failedAttempts.forEach((id, attempts) -> {
      try {
        bankClient.reverse(id);
      } catch (AcquiringBankException e) {
        recordFailure(id, attempts + 1, e);
        return;
      }
      failedAttempts.remove(id);
      paymentsRepository.get(id)
          .ifPresent(payment -> paymentsRepository.save(payment.withStatus(PaymentStatus.DECLINED)));
      meterRegistry.counter("payments.reversals", "outcome", "reversed").increment();
      LOG.info("Payment {} Declined: authorization reversed after unknown bank outcome", id);
    });
  }

  private void recordFailure(UUID id, int attempts, AcquiringBankException e) {
    if (attempts < maxAttempts) {
      failedAttempts.put(id, attempts);
      LOG.warn("Reversal of payment {} failed (attempt {}/{}): {}", id, attempts, maxAttempts,
          e.getMessage());
      return;
    }
    failedAttempts.remove(id);
    meterRegistry.counter("payments.reversals", "outcome", "failed").increment();
    LOG.error("Payment {} left Pending after {} reversal attempts: manual reconciliation required",
        id, attempts, e);
  }
}
