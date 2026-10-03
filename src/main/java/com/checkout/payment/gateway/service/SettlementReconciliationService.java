package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.model.Payment;
import com.checkout.payment.gateway.repository.PaymentStore;
import com.checkout.payment.gateway.repository.ReconciliationReportsRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class SettlementReconciliationService {

  private static final Logger LOG = LoggerFactory.getLogger(SettlementReconciliationService.class);

  private final PaymentStore paymentStore;
  private final ReconciliationReportsRepository reportsRepository;
  private final MeterRegistry meterRegistry;
  private final Clock clock;

  public SettlementReconciliationService(PaymentStore paymentStore,
      ReconciliationReportsRepository reportsRepository, MeterRegistry meterRegistry, Clock clock) {
    this.paymentStore = paymentStore;
    this.reportsRepository = reportsRepository;
    this.meterRegistry = meterRegistry;
    this.clock = clock;
  }

  public void reconcile(Path file, LocalDate reportDate) throws IOException {
    List<SettlementRow> rows = readRows(file);
    Instant dayStart = reportDate.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant dayEnd = reportDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    List<Payment> payments = paymentStore.findCreatedBetween(dayStart, dayEnd);
    List<ReconciliationIssue> issues = compare(rows, payments);
    try {
      reportsRepository.save(reportDate, file.getFileName().toString(), clock.instant(), rows.size(),
          issues);
    } catch (DuplicateKeyException e) {
      LOG.info("Settlement report for {} was already reconciled", reportDate);
      meterRegistry.counter("payments.reconciliation.reports", "outcome", "duplicate").increment();
      return;
    }

    meterRegistry.counter("payments.reconciliation.reports", "outcome", "completed").increment();
    meterRegistry.counter("payments.reconciliation.issues").increment(issues.size());
    LOG.info("Reconciled settlement report {}: {} rows, {} issues", reportDate, rows.size(),
        issues.size());
    issues.forEach(issue -> LOG.error("Settlement reconciliation issue {} for reference {}: {}",
        issue.type(), issue.reference(), issue.details()));
  }

  public boolean isProcessed(LocalDate reportDate) {
    return reportsRepository.isProcessed(reportDate);
  }

  private static List<SettlementRow> readRows(Path file) throws IOException {
    CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
    try (var reader = Files.newBufferedReader(file);
        CSVParser parser = format.parse(reader)) {
      if (!parser.getHeaderMap().keySet()
          .containsAll(Set.of("reference", "amount", "currency", "status"))) {
        throw new IllegalArgumentException(
            "Settlement CSV must have reference,amount,currency,status columns");
      }
      List<SettlementRow> rows = new ArrayList<>();
      for (CSVRecord record : parser) {
        try {
          int amount = Integer.parseInt(record.get("amount").trim());
          String currency = record.get("currency").trim();
          if (amount <= 0 || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Invalid settlement amount or currency");
          }
          rows.add(new SettlementRow(UUID.fromString(record.get("reference").trim()), amount,
              currency, parseStatus(record.get("status").trim())));
        } catch (RuntimeException e) {
          throw new IllegalArgumentException(
              "Invalid settlement row " + record.getRecordNumber(), e);
        }
      }
      return rows;
    }
  }

  private static PaymentStatus parseStatus(String status) {
    return switch (status.toUpperCase()) {
      case "AUTHORIZED" -> PaymentStatus.AUTHORIZED;
      case "DECLINED" -> PaymentStatus.DECLINED;
      case "PENDING" -> PaymentStatus.PENDING;
      default -> throw new IllegalArgumentException("Unsupported settlement status");
    };
  }

  private static List<ReconciliationIssue> compare(List<SettlementRow> rows,
      List<Payment> payments) {
    List<ReconciliationIssue> issues = new ArrayList<>();
    Map<UUID, Payment> paymentsById = payments.stream()
        .collect(Collectors.toMap(Payment::id, Function.identity()));
    Set<UUID> seenReferences = new HashSet<>();
    for (SettlementRow row : rows) {
      if (!seenReferences.add(row.reference())) {
        issues.add(new ReconciliationIssue("DUPLICATE_BANK_REFERENCE", row.reference().toString(),
            "The settlement file contains this reference more than once"));
        continue;
      }
      Payment payment = paymentsById.get(row.reference());
      if (payment == null) {
        issues.add(new ReconciliationIssue("UNKNOWN_BANK_REFERENCE", row.reference().toString(),
            "The bank reported a reference not recorded by the gateway"));
        continue;
      }
      if (payment.amount() != row.amount()) {
        issues.add(new ReconciliationIssue("AMOUNT_MISMATCH", row.reference().toString(),
            "Gateway amount " + payment.amount() + " differs from bank amount " + row.amount()));
      }
      if (!payment.currency().equals(row.currency())) {
        issues.add(new ReconciliationIssue("CURRENCY_MISMATCH", row.reference().toString(),
            "Gateway currency " + payment.currency() + " differs from bank currency "
                + row.currency()));
      }
      if (payment.status() != row.status()) {
        issues.add(new ReconciliationIssue("STATUS_MISMATCH", row.reference().toString(),
            "Gateway status " + payment.status().name() + " differs from bank status "
                + row.status().name()));
      }
    }

    for (Payment payment : payments) {
      if (!seenReferences.contains(payment.id())) {
        issues.add(new ReconciliationIssue("MISSING_BANK_REFERENCE", payment.id().toString(),
            "The gateway payment is absent from the settlement file"));
      }
      if (payment.status() == PaymentStatus.PENDING) {
        issues.add(new ReconciliationIssue("PENDING_PAYMENT", payment.id().toString(),
            "A payment remains Pending and requires investigation"));
      }
    }
    return issues;
  }

  private record SettlementRow(UUID reference, int amount, String currency, PaymentStatus status) {
  }
}