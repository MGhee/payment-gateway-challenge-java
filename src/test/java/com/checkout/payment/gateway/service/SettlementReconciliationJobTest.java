package com.checkout.payment.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettlementReconciliationJobTest {

  private static final LocalDate YESTERDAY = LocalDate.of(2026, 10, 3);

  @TempDir
  Path directory;

  private final SettlementReconciliationService service =
      mock(SettlementReconciliationService.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T02:00:00Z"), ZoneOffset.UTC);

  @Test
  void reconcilesYesterdayAndEarlierUnprocessedReportsOnly() throws IOException {
    Path yesterday = report("settlement-2026-10-03.csv");
    Path late = report("settlement-2026-10-01.csv");
    report("settlement-2026-10-02.csv");
    report("settlement-2026-10-04.csv");
    report("settlement-latest.csv");
    when(service.isProcessed(LocalDate.of(2026, 10, 2))).thenReturn(true);

    job(directory).reconcilePreviousDay();

    verify(service).reconcile(yesterday, YESTERDAY);
    verify(service).reconcile(late, LocalDate.of(2026, 10, 1));
    verify(service, never()).reconcile(any(), eq(LocalDate.of(2026, 10, 2)));
    verify(service, never()).reconcile(any(), eq(LocalDate.of(2026, 10, 4)));
    assertThat(outcome("missing")).isZero();
  }

  @Test
  void missingReportForYesterdayIsCounted() throws IOException {
    report("settlement-2026-10-01.csv");

    job(directory).reconcilePreviousDay();

    assertThat(outcome("missing")).isOne();
  }

  @Test
  void missingDirectoryIsCountedAsAMissingReport() throws IOException {
    job(directory.resolve("absent")).reconcilePreviousDay();

    verify(service, never()).reconcile(any(), any());
    assertThat(outcome("missing")).isOne();
  }

  @Test
  void invalidReportIsCountedAndDoesNotStopTheOthers() throws IOException {
    Path invalid = report("settlement-2026-10-02.csv");
    Path yesterday = report("settlement-2026-10-03.csv");
    doThrow(new IllegalArgumentException("bad header")).when(service).reconcile(eq(invalid), any());

    job(directory).reconcilePreviousDay();

    verify(service).reconcile(yesterday, YESTERDAY);
    assertThat(outcome("invalid")).isOne();
  }

  private SettlementReconciliationJob job(Path reportDirectory) {
    return new SettlementReconciliationJob(service, meterRegistry, clock,
        reportDirectory.toString());
  }

  private Path report(String name) throws IOException {
    return Files.writeString(directory.resolve(name), "reference,amount,currency,status\n");
  }

  private double outcome(String outcome) {
    return meterRegistry.counter("payments.reconciliation.reports", "outcome", outcome).count();
  }
}
