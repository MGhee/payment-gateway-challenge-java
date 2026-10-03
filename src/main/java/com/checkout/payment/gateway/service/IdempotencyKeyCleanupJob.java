package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.repository.PaymentStore;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyKeyCleanupJob {

  private final PaymentStore paymentStore;
  private final Clock clock;

  public IdempotencyKeyCleanupJob(PaymentStore paymentStore, Clock clock) {
    this.paymentStore = paymentStore;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${gateway.idempotency-cleanup-interval:PT1H}")
  public void cleanupExpiredKeys() {
    paymentStore.purgeExpiredIdempotencyKeys(clock.instant());
  }
}