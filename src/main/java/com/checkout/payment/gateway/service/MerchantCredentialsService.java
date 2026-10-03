package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.model.ApiKeyCredential;
import com.checkout.payment.gateway.repository.MerchantApiKeysRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantCredentialsService {

  private static final SecureRandom RANDOM = new SecureRandom();
  private final MerchantApiKeysRepository repository;
  private final Clock clock;
  private final Duration keyValidity;

  public MerchantCredentialsService(MerchantApiKeysRepository repository, Clock clock,
      @Value("${gateway.api-key-validity:365d}") Duration keyValidity) {
    this.repository = repository;
    this.clock = clock;
    this.keyValidity = keyValidity;
  }

  public ApiKeyCredential provision(String merchantId) {
    return issue(merchantId);
  }

  @Transactional
  public ApiKeyCredential rotate(String merchantId, UUID keyId) {
    if (!repository.revoke(keyId, merchantId, clock.instant())) {
      throw new MerchantCredentialNotFoundException(keyId);
    }
    return issue(merchantId);
  }

  public void revoke(String merchantId, UUID keyId) {
    if (!repository.revoke(keyId, merchantId, clock.instant())) {
      throw new MerchantCredentialNotFoundException(keyId);
    }
  }

  public String authenticate(String apiKey) {
    if (apiKey == null || apiKey.isBlank()) {
      return null;
    }
    return repository.findActiveMerchant(hash(apiKey), clock.instant());
  }

  private ApiKeyCredential issue(String merchantId) {
    byte[] secretBytes = new byte[32];
    RANDOM.nextBytes(secretBytes);
    String apiKey = "pgw_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
    String prefix = apiKey.substring(0, 12);
    String digest = hash(apiKey);
    UUID keyId = UUID.randomUUID();
    Instant createdAt = clock.instant();
    Instant expiresAt = createdAt.plus(keyValidity);
    repository.save(keyId, merchantId, prefix, digest, createdAt, expiresAt);
    return new ApiKeyCredential(keyId, apiKey, prefix, expiresAt);
  }

  private static String hash(String secret) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(secret.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}