package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.configuration.BankProperties;
import com.checkout.payment.gateway.repository.PaymentStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentAuthorizationRecoveryJob {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentAuthorizationRecoveryJob.class);

  private final PaymentStore paymentStore;
  private final Clock clock;
  private final Duration staleAfter;

  public PaymentAuthorizationRecoveryJob(PaymentStore paymentStore, BankProperties bank,
      Clock clock, @Value("${bank.authorization-recovery-grace-period:30s}") Duration gracePeriod) {
    this.paymentStore = paymentStore;
    this.clock = clock;
    this.staleAfter = bank.connectTimeout().plus(bank.readTimeout()).plus(gracePeriod);
  }

  @Scheduled(fixedDelayString = "${bank.authorization-recovery-interval:PT30S}")
  public void recoverStaleAuthorizations() {
    Instant now = clock.instant();
    int recovered = paymentStore.recoverStaleAuthorizations(now.minus(staleAfter), now);
    if (recovered > 0) {
      LOG.error("Recovered {} stale in-flight payments as unknown outcomes for reversal", recovered);
    }
  }
}