package com.checkout.payment.gateway.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MerchantApiKeysRepository {

  private final JdbcTemplate jdbcTemplate;

  public MerchantApiKeysRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void save(UUID id, String merchantId, String prefix, String hash, Instant createdAt,
      Instant expiresAt) {
    jdbcTemplate.update("INSERT INTO merchant_api_keys "
            + "(id, merchant_id, key_prefix, key_hash, created_at, expires_at) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        id, merchantId, prefix, hash, Timestamp.from(createdAt), Timestamp.from(expiresAt));
  }

  public String findActiveMerchant(String keyHash, Instant now) {
    try {
      return jdbcTemplate.queryForObject("SELECT merchant_id FROM merchant_api_keys "
              + "WHERE key_hash = ? AND revoked_at IS NULL AND expires_at > ?",
          String.class, keyHash, Timestamp.from(now));
    } catch (EmptyResultDataAccessException e) {
      return null;
    }
  }

  public boolean revoke(UUID keyId, String merchantId, Instant revokedAt) {
    return jdbcTemplate.update("UPDATE merchant_api_keys SET revoked_at = ? "
            + "WHERE id = ? AND merchant_id = ? AND revoked_at IS NULL",
        Timestamp.from(revokedAt), keyId, merchantId) == 1;
  }
}