package com.checkout.payment.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.model.Payment;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.repository.InMemoryPaymentStore;
import com.checkout.payment.gateway.repository.ReconciliationReportsRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettlementReconciliationServiceTest {

  @TempDir
  Path temporaryDirectory;

  private final InMemoryPaymentStore payments = new InMemoryPaymentStore();
  private final ReconciliationReportsRepository reports = mock(ReconciliationReportsRepository.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final SettlementReconciliationService service = new SettlementReconciliationService(
      payments, reports, meterRegistry, Clock.systemUTC());
  private List<ReconciliationIssue> savedIssues;

  @BeforeEach
  void captureSavedIssues() {
    doAnswer(invocation -> {
      savedIssues = invocation.getArgument(4);
      return null;
    }).when(reports).save(any(), any(), any(), anyInt(), anyList());
  }

  @Test
  void matchingSettlementRowIsRecordedWithoutIssues() throws IOException {
    Payment payment = payment(PaymentStatus.AUTHORIZED);
    Path file = writeReport("reference,amount,currency,status\n"
        + payment.id() + ",100,GBP,AUTHORIZED\n");

    service.reconcile(file, today());

    assertThat(savedIssues).isEmpty();
    verify(reports).save(eq(today()), eq(file.getFileName().toString()), any(), eq(1), anyList());
    assertThat(meterRegistry.counter("payments.reconciliation.reports", "outcome", "completed")
        .count()).isEqualTo(1);
  }

  @Test
  void mismatchesUnknownDuplicateMissingAndPendingPaymentsAreReported() throws IOException {
    Payment authorized = payment(PaymentStatus.AUTHORIZED);
    payment(PaymentStatus.DECLINED);
    Payment pending = payment(PaymentStatus.PENDING);
    UUID unknownReference = UUID.randomUUID();
    Path file = writeReport("reference,amount,currency,status\n"
        + authorized.id() + ",999,USD,DECLINED\n"
        + unknownReference + ",1,GBP,AUTHORIZED\n"
        + unknownReference + ",1,GBP,AUTHORIZED\n"
        + pending.id() + ",100,GBP,PENDING\n");

    service.reconcile(file, today());

    assertThat(savedIssues).extracting(ReconciliationIssue::type).containsExactlyInAnyOrder(
        "AMOUNT_MISMATCH", "CURRENCY_MISMATCH", "STATUS_MISMATCH", "UNKNOWN_BANK_REFERENCE",
        "DUPLICATE_BANK_REFERENCE", "PENDING_PAYMENT", "MISSING_BANK_REFERENCE");
    assertThat(meterRegistry.counter("payments.reconciliation.issues").count()).isEqualTo(7);
  }

  @Test
  void invalidHeaderIsRejectedBeforeSavingAReport() throws IOException {
    Path file = writeReport("id,total\n1,100\n");

    assertThatThrownBy(() -> service.reconcile(file, today()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reference,amount,currency,status");
    verifyNoInteractions(reports);
  }

  private Payment payment(PaymentStatus status) {
    Payment payment = Payment.pending(
        new PostPaymentRequest("2222405343248877", 4, 2030, "GBP", 100, "123"), null,
        "test-merchant");
    Payment stored = status == PaymentStatus.PENDING
        ? payment.withUnknownOutcome(payment.createdAt())
        : payment.withStatus(status);
    payments.save(stored);
    return stored;
  }

  private Path writeReport(String contents) throws IOException {
    Path file = temporaryDirectory.resolve("settlement-" + today() + ".csv");
    return Files.writeString(file, contents);
  }

  private LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }
}