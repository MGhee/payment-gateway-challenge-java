package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.model.Payment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PaymentsRepository implements PaymentStore {

  private static final String COLUMNS = "id, merchant_id, status, card_number_last_four, "
      + "expiry_month, expiry_year, currency, amount, idempotency_key, created_at, "
      + "authorization_in_progress, reversal_attempts, next_reversal_at";

  private final JdbcTemplate jdbcTemplate;

  public PaymentsRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<Payment> addIfKeyUnused(Payment payment) {
    String sql = "INSERT INTO payments (" + COLUMNS
        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    try {
      jdbcTemplate.update(sql, payment.id(), payment.merchantId(), payment.status().name(),
          payment.cardNumberLastFour(), payment.expiryMonth(), payment.expiryYear(),
          payment.currency(), payment.amount(), payment.idempotencyKey(),
          Timestamp.from(payment.createdAt()), payment.authorizationInProgress(),
          payment.reversalAttempts(), timestamp(payment.nextReversalAt()));
    } catch (DuplicateKeyException e) {
      Optional<Payment> original = payment.idempotencyKey() == null ? Optional.empty()
          : getByIdempotencyKey(payment.merchantId(), payment.idempotencyKey());
      if (original.isPresent()) {
        return original;
      }
      throw e;
    }
    return Optional.empty();
  }

  @Override
  @Transactional
  public void save(Payment payment) {
    int updated = jdbcTemplate.update("UPDATE payments SET status = ?, authorization_in_progress = ?, "
            + "reversal_attempts = ?, next_reversal_at = ? WHERE id = ? AND merchant_id = ?",
        payment.status().name(), payment.authorizationInProgress(), payment.reversalAttempts(),
        timestamp(payment.nextReversalAt()), payment.id(), payment.merchantId());
    if (updated != 1) {
      throw new IllegalStateException("Payment disappeared before it could be updated");
    }
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
    return queryOne("SELECT " + COLUMNS + " FROM payments WHERE merchant_id = ? "
        + "AND idempotency_key = ?", merchantId, idempotencyKey);
  }

  @Override
  @Transactional
  public void remove(Payment payment) {
    jdbcTemplate.update("DELETE FROM payments WHERE id = ? AND merchant_id = ?",
        payment.id(), payment.merchantId());
  }

  @Override
  public List<Payment> findDueReversals(Instant now) {
    return jdbcTemplate.query("SELECT " + COLUMNS + " FROM payments WHERE status = ? "
            + "AND authorization_in_progress = FALSE AND next_reversal_at <= ?",
        PAYMENT_MAPPER, PaymentStatus.PENDING.name(), Timestamp.from(now));
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
