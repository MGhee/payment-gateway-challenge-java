package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.service.ReconciliationIssue;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReconciliationReportsRepository {

  private final JdbcTemplate jdbcTemplate;

  public ReconciliationReportsRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public boolean isProcessed(LocalDate reportDate) {
    Boolean exists = jdbcTemplate.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM reconciliation_reports WHERE report_date = ?)",
        Boolean.class, Date.valueOf(reportDate));
    return Boolean.TRUE.equals(exists);
  }

  @Transactional
  public void save(LocalDate reportDate, String sourceFile, Instant importedAt, int rowCount,
      List<ReconciliationIssue> issues) {
    UUID reportId = UUID.randomUUID();
    jdbcTemplate.update("INSERT INTO reconciliation_reports "
            + "(id, report_date, source_file, imported_at, row_count, issue_count) "
            + "VALUES (?, ?, ?, ?, ?, ?)", reportId, Date.valueOf(reportDate), sourceFile,
        Timestamp.from(importedAt), rowCount, issues.size());
    if (!issues.isEmpty()) {
      jdbcTemplate.batchUpdate("INSERT INTO reconciliation_issues "
              + "(report_id, issue_type, reference, details) VALUES (?, ?, ?, ?)",
          issues, 100, (statement, issue) -> {
            statement.setObject(1, reportId);
            statement.setString(2, issue.type());
            statement.setString(3, issue.reference());
            statement.setString(4, issue.details());
          });
    }
  }
}