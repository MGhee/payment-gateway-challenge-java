package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.IdempotencyConflictException;
import com.checkout.payment.gateway.model.Payment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PaymentsRepository implements PaymentStore {

  private static final String COLUMNS = "id, merchant_id, status, card_number_last_four, "
      + "expiry_month, expiry_year, currency, amount, idempotency_key, created_at, "
      + "authorization_in_progress, reversal_attempts, next_reversal_at";
  private static final int MAX_RESERVATION_ATTEMPTS = 3;

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;
  private final java.time.Clock clock;
  private final Duration idempotencyRetention;

  public PaymentsRepository(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
      java.time.Clock clock,
      @Value("${gateway.idempotency-retention:PT72H}") Duration idempotencyRetention) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.clock = clock;
    this.idempotencyRetention = idempotencyRetention;
  }

  @Override
  public Optional<Payment> addIfKeyUnused(Payment payment) {
    if (payment.idempotencyKey() == null) {
      insertPayment(payment);
      return Optional.empty();
    }
    // The conflicting payment can be deleted (a bank failure frees its key) before it is read back
    for (int attempt = 0; attempt < MAX_RESERVATION_ATTEMPTS; attempt++) {
      try {
        reserveKey(payment);
        return Optional.empty();
      } catch (DuplicateKeyException e) {
        Optional<Payment> original =
            getByIdempotencyKey(payment.merchantId(), payment.idempotencyKey());
        if (original.isPresent()) {
          return original;
        }
      }
    }
    throw new IdempotencyConflictException();
  }

  private void reserveKey(Payment payment) {
    transactionTemplate.executeWithoutResult(transaction -> {
      Instant now = clock.instant();
      clearExpiredIdempotencyKey(payment.merchantId(), payment.idempotencyKey(), now);
      insertPayment(payment);
      jdbcTemplate.update("INSERT INTO payment_idempotency_keys "
              + "(merchant_id, idempotency_key, payment_id, expires_at) VALUES (?, ?, ?, ?)",
          payment.merchantId(), payment.idempotencyKey(), payment.id(),
          Timestamp.from(now.plus(idempotencyRetention)));
    });
  }

  @Override
  public boolean transition(Payment current, Payment next) {
    return jdbcTemplate.update("UPDATE payments SET status = ?, authorization_in_progress = ?, "
            + "reversal_attempts = ?, next_reversal_at = ? WHERE id = ? AND merchant_id = ? "
            + "AND status = ? AND authorization_in_progress = ? AND reversal_attempts = ?",
        next.status().name(), next.authorizationInProgress(), next.reversalAttempts(),
        timestamp(next.nextReversalAt()), current.id(), current.merchantId(),
        current.status().name(), current.authorizationInProgress(),
        current.reversalAttempts()) == 1;
  }

  @Override
  public Optional<Payment> get(UUID id) {
    return queryOne("SELECT " + COLUMNS + " FROM payments WHERE id = ?", id);
  }

  @Override
  public Optional<Payment> get(UUID id, String merchantId) {
    return queryOne("SELECT " + COLUMNS + " FROM payments WHERE id = ? AND merchant_id = ?",
        id, merchantId);
  }

  @Override
  public Optional<Payment> getByIdempotencyKey(String merchantId, String idempotencyKey) {
    return queryOne("SELECT p." + COLUMNS.replace(", ", ", p.") + " FROM payments p "
        + "JOIN payment_idempotency_keys k ON k.payment_id = p.id "
        + "WHERE k.merchant_id = ? AND k.idempotency_key = ? AND k.expires_at > ?",
        merchantId, idempotencyKey, Timestamp.from(clock.instant()));
  }

  @Override
  public boolean removeInFlight(Payment payment) {
    return jdbcTemplate.update("DELETE FROM payments WHERE id = ? AND merchant_id = ? "
            + "AND status = ? AND authorization_in_progress = TRUE",
        payment.id(), payment.merchantId(), PaymentStatus.PENDING.name()) == 1;
  }

  @Override
  public List<Payment> claimDueReversals(Instant now, Instant leaseUntil, int limit) {
    return transactionTemplate.execute(transaction -> {
      List<Payment> due = jdbcTemplate.query("SELECT " + COLUMNS + " FROM payments "
              + "WHERE status = ? AND authorization_in_progress = FALSE AND next_reversal_at <= ? "
              + "ORDER BY next_reversal_at LIMIT ? FOR UPDATE SKIP LOCKED",
          PAYMENT_MAPPER, PaymentStatus.PENDING.name(), Timestamp.from(now), limit);
      due.forEach(payment -> jdbcTemplate.update(
          "UPDATE payments SET next_reversal_at = ? WHERE id = ?",
          Timestamp.from(leaseUntil), payment.id()));
      return due.stream().map(payment -> payment.withNextReversalAt(leaseUntil)).toList();
    });
  }

  @Override
  public List<Payment> findCreatedBetween(Instant startInclusive, Instant endExclusive) {
    return jdbcTemplate.query("SELECT " + COLUMNS + " FROM payments WHERE created_at >= ? "
            + "AND created_at < ? ORDER BY created_at",
        PAYMENT_MAPPER, Timestamp.from(startInclusive), Timestamp.from(endExclusive));
  }

  @Override
  @Transactional
  public int recoverStaleAuthorizations(Instant startedBefore, Instant retryAt) {
    return jdbcTemplate.update("UPDATE payments SET authorization_in_progress = FALSE, "
            + "next_reversal_at = ? WHERE status = ? AND authorization_in_progress = TRUE "
            + "AND created_at < ?",
        Timestamp.from(retryAt), PaymentStatus.PENDING.name(), Timestamp.from(startedBefore));
  }

  @Override
  @Transactional
  public int purgeExpiredIdempotencyKeys(Instant now) {
    jdbcTemplate.update("UPDATE payments SET idempotency_key = NULL WHERE id IN "
        + "(SELECT payment_id FROM payment_idempotency_keys WHERE expires_at <= ?)",
        Timestamp.from(now));
    return jdbcTemplate.update("DELETE FROM payment_idempotency_keys WHERE expires_at <= ?",
        Timestamp.from(now));
  }

  private void clearExpiredIdempotencyKey(String merchantId, String idempotencyKey, Instant now) {
    Timestamp timestamp = Timestamp.from(now);
    jdbcTemplate.update("UPDATE payments SET idempotency_key = NULL WHERE id IN "
        + "(SELECT payment_id FROM payment_idempotency_keys WHERE merchant_id = ? "
        + "AND idempotency_key = ? AND expires_at <= ?)",
        merchantId, idempotencyKey, timestamp);
    jdbcTemplate.update("DELETE FROM payment_idempotency_keys WHERE merchant_id = ? "
        + "AND idempotency_key = ? AND expires_at <= ?",
        merchantId, idempotencyKey, timestamp);
  }

  private void insertPayment(Payment payment) {
    String sql = "INSERT INTO payments (" + COLUMNS
        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    jdbcTemplate.update(sql, payment.id(), payment.merchantId(), payment.status().name(),
        payment.cardNumberLastFour(), payment.expiryMonth(), payment.expiryYear(),
        payment.currency(), payment.amount(), payment.idempotencyKey(),
        Timestamp.from(payment.createdAt()), payment.authorizationInProgress(),
        payment.reversalAttempts(), timestamp(payment.nextReversalAt()));
  }

  private Optional<Payment> queryOne(String sql, Object... args) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject(sql, PAYMENT_MAPPER, args));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static final RowMapper<Payment> PAYMENT_MAPPER = PaymentsRepository::mapPayment;

  private static Payment mapPayment(ResultSet rs, @SuppressWarnings("unused") int rowNum)
      throws SQLException {
    Timestamp nextReversal = rs.getTimestamp("next_reversal_at");
    return new Payment(rs.getObject("id", UUID.class), rs.getString("merchant_id"),
        PaymentStatus.valueOf(rs.getString("status")), rs.getString("card_number_last_four"),
        rs.getInt("expiry_month"), rs.getInt("expiry_year"), rs.getString("currency"),
        rs.getInt("amount"), rs.getString("idempotency_key"),
        rs.getTimestamp("created_at").toInstant(), rs.getBoolean("authorization_in_progress"),
        rs.getInt("reversal_attempts"), nextReversal == null ? null : nextReversal.toInstant());
  }
}
