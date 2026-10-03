package com.checkout.payment.gateway.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.DirectoryStream;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SettlementReconciliationJob {

  private static final Logger LOG = LoggerFactory.getLogger(SettlementReconciliationJob.class);

  private final SettlementReconciliationService reconciliationService;
  private final MeterRegistry meterRegistry;
  private final Clock clock;
  private final Path directory;

  public SettlementReconciliationJob(SettlementReconciliationService reconciliationService,
      MeterRegistry meterRegistry, Clock clock,
      @Value("${bank.reconciliation.directory}") String directory) {
    this.reconciliationService = reconciliationService;
    this.meterRegistry = meterRegistry;
    this.clock = clock;
    this.directory = Path.of(directory);
  }

  @Scheduled(cron = "${bank.reconciliation.cron}", zone = "UTC")
  public void reconcilePreviousDay() {
    LocalDate yesterday = LocalDate.now(clock).minusDays(1);
    if (!Files.isDirectory(directory)) {
      recordMissing(yesterday);
      return;
    }
    boolean yesterdayFound = false;
    try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "settlement-*.csv")) {
      for (Path file : files) {
        LocalDate reportDate = reportDate(file);
        if (reportDate == null || reportDate.isAfter(yesterday)) {
          continue;
        }
        if (reportDate.equals(yesterday)) {
          yesterdayFound = true;
        }
        if (reconciliationService.isProcessed(reportDate)) {
          continue;
        }
        try {
          reconciliationService.reconcile(file, reportDate);
        } catch (IOException | IllegalArgumentException e) {
          LOG.error("Could not reconcile settlement report {}", file, e);
          meterRegistry.counter("payments.reconciliation.reports", "outcome", "invalid")
              .increment();
        }
      }
    } catch (IOException e) {
      LOG.error("Could not list settlement reports in {}", directory, e);
      meterRegistry.counter("payments.reconciliation.reports", "outcome", "invalid").increment();
    }
    if (!yesterdayFound) {
      recordMissing(yesterday);
    }
  }

  private void recordMissing(LocalDate reportDate) {
    Path file = directory.resolve("settlement-" + reportDate + ".csv");
    if (!Files.isRegularFile(file)) {
      LOG.error("Settlement report is missing for {} at {}", reportDate, file);
      meterRegistry.counter("payments.reconciliation.reports", "outcome", "missing").increment();
    }
  }

  private static LocalDate reportDate(Path file) {
    String name = file.getFileName().toString();
    if (!name.matches("settlement-\\d{4}-\\d{2}-\\d{2}\\.csv")) {
      return null;
    }
    try {
      return LocalDate.parse(name.substring("settlement-".length(), name.length() - ".csv".length()));
    } catch (RuntimeException e) {
      return null;
    }
  }
}