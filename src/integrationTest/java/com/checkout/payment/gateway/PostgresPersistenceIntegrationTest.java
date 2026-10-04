package com.checkout.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.model.Payment;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import com.checkout.payment.gateway.service.PaymentAuthorizationRecoveryJob;
import com.checkout.payment.gateway.service.SettlementReconciliationService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

class PostgresPersistenceIntegrationTest extends IntegrationTestBase {

  private static final PostPaymentRequest REQUEST =
      new PostPaymentRequest("2222405343248877", 4, NEXT_YEAR, "GBP", 100, "123");

  @Autowired
  private PaymentsRepository payments;
  @Autowired
  private PaymentAuthorizationRecoveryJob recoveryJob;
  @Autowired
  private SettlementReconciliationService reconciliation;

  @TempDir
  Path settlementDirectory;

  private final String merchantId = "it-" + UUID.randomUUID();

  @Test
  void paymentRoundTripsThroughPostgresUnchanged() {
    Payment payment = Payment.pending(REQUEST, "round-trip", merchantId);

    payments.addIfKeyUnused(payment);

    assertThat(payments.get(payment.id(), merchantId)).contains(payment);
    assertThat(payments.getByIdempotencyKey(merchantId, "round-trip")).contains(payment);
  }

  @Test
  void duplicateIdempotencyKeyReturnsTheOriginalPayment() {
    Payment original = Payment.pending(REQUEST, "duplicate", merchantId);
    Payment duplicate = Payment.pending(REQUEST, "duplicate", merchantId);

    assertThat(payments.addIfKeyUnused(original)).isEmpty();
    assertThat(payments.addIfKeyUnused(duplicate)).contains(original);
    assertThat(payments.get(duplicate.id())).isEmpty();
  }

  @Test
  void expiredIdempotencyKeyIsPurgedAndCanStartANewPayment() {
    Payment original = Payment.pending(REQUEST, "expiring", merchantId);
    payments.addIfKeyUnused(original);

    payments.purgeExpiredIdempotencyKeys(Instant.now().plus(Duration.ofHours(73)));

    Payment next = Payment.pending(REQUEST, "expiring", merchantId);
    assertThat(payments.addIfKeyUnused(next)).isEmpty();
    assertThat(payments.get(original.id())).get().extracting(Payment::idempotencyKey).isNull();
  }

  @Test
  void staleInFlightAuthorizationBecomesDueForReversal() {
    Payment fresh = Payment.pending(REQUEST, null, merchantId);
    Payment stale = createdAt(fresh, fresh.createdAt().minus(Duration.ofHours(1)));
    payments.addIfKeyUnused(stale);

    recoveryJob.recoverStaleAuthorizations();

    assertThat(payments.findDueReversals(Instant.now())).extracting(Payment::id)
        .contains(stale.id());
  }

  @Test
  void settlementReportIsReconciledOncePerDay() throws IOException {
    LocalDate reportDate = LocalDate.of(2001, 2, 3);
    Payment settled = createdAt(Payment.pending(REQUEST, null, merchantId)
        .withStatus(PaymentStatus.AUTHORIZED), reportDate.atTime(12, 0).toInstant(ZoneOffset.UTC));
    payments.addIfKeyUnused(settled);
    Path report = Files.writeString(settlementDirectory.resolve("settlement-2001-02-03.csv"),
        "reference,amount,currency,status\n"
            + settled.id() + ",100,GBP,AUTHORIZED\n"
            + UUID.randomUUID() + ",250,GBP,AUTHORIZED\n");

    reconciliation.reconcile(report, reportDate);
    reconciliation.reconcile(report, reportDate);

    assertThat(reconciliation.isProcessed(reportDate)).isTrue();
    assertThat(jdbc.queryForList("SELECT i.issue_type FROM reconciliation_issues i "
            + "JOIN reconciliation_reports r ON r.id = i.report_id WHERE r.report_date = ?",
        String.class, reportDate)).containsExactly("UNKNOWN_BANK_REFERENCE");
  }

  private static Payment createdAt(Payment payment, Instant createdAt) {
    return new Payment(payment.id(), payment.merchantId(), payment.status(),
        payment.cardNumberLastFour(), payment.expiryMonth(), payment.expiryYear(),
        payment.currency(), payment.amount(), payment.idempotencyKey(), createdAt,
        payment.authorizationInProgress(), payment.reversalAttempts(), payment.nextReversalAt());
  }
}
