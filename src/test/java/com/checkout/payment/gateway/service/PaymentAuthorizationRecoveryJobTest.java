package com.checkout.payment.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.checkout.payment.gateway.configuration.BankProperties;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.model.Payment;
import com.checkout.payment.gateway.repository.InMemoryPaymentStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentAuthorizationRecoveryJobTest {

  private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

  private final InMemoryPaymentStore payments = new InMemoryPaymentStore();
  private final PaymentAuthorizationRecoveryJob job = new PaymentAuthorizationRecoveryJob(payments,
      new BankProperties("http://bank", Duration.ofSeconds(2), Duration.ofSeconds(10), 5,
          Duration.ofSeconds(10)),
      Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(1));

  @Test
  void staleInFlightPaymentBecomesDurableReversalWork() {
    Payment stale = payment(NOW.minusSeconds(20));
    Payment recent = payment(NOW.minusSeconds(1));
    payments.save(stale);
    payments.save(recent);

    job.recoverStaleAuthorizations();

    Payment recovered = payments.get(stale.id()).orElseThrow();
    assertThat(recovered.authorizationInProgress()).isFalse();
    assertThat(recovered.nextReversalAt()).isEqualTo(NOW);
    assertThat(payments.get(recent.id()).orElseThrow().authorizationInProgress()).isTrue();
  }

  private static Payment payment(Instant createdAt) {
    return new Payment(UUID.randomUUID(), "merchant", PaymentStatus.PENDING, "8877", 4, 2030,
        "GBP", 100, null, createdAt, true, 0, null);
  }
}