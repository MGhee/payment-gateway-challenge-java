package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.model.Payment;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryPaymentStore implements PaymentStore {

  private final Map<UUID, Payment> payments = new ConcurrentHashMap<>();
  private final Map<String, UUID> paymentIdsByIdempotencyKey = new ConcurrentHashMap<>();

  @Override
  public synchronized Optional<Payment> addIfKeyUnused(Payment payment) {
    if (payment.idempotencyKey() != null) {
      UUID originalId = paymentIdsByIdempotencyKey.putIfAbsent(
          key(payment.merchantId(), payment.idempotencyKey()), payment.id());
      if (originalId != null) {
        return Optional.ofNullable(payments.get(originalId));
      }
    }
    payments.put(payment.id(), payment);
    return Optional.empty();
  }

  public void save(Payment payment) {
    payments.put(payment.id(), payment);
  }

  @Override
  public synchronized boolean transition(Payment current, Payment next) {
    Payment stored = payments.get(current.id());
    if (stored == null || stored.status() != current.status()
        || stored.authorizationInProgress() != current.authorizationInProgress()
        || stored.reversalAttempts() != current.reversalAttempts()) {
      return false;
    }
    payments.put(next.id(), next);
    return true;
  }

  @Override
  public Optional<Payment> get(UUID id) {
    return Optional.ofNullable(payments.get(id));
  }

  @Override
  public Optional<Payment> get(UUID id, String merchantId) {
    return get(id).filter(payment -> payment.merchantId().equals(merchantId));
  }

  @Override
  public Optional<Payment> getByIdempotencyKey(String merchantId, String idempotencyKey) {
    UUID id = paymentIdsByIdempotencyKey.get(key(merchantId, idempotencyKey));
    return id == null ? Optional.empty() : get(id);
  }

  @Override
  public synchronized boolean removeInFlight(Payment payment) {
    Payment stored = payments.get(payment.id());
    if (stored == null || stored.status() != PaymentStatus.PENDING
        || !stored.authorizationInProgress()) {
      return false;
    }
    payments.remove(payment.id());
    if (payment.idempotencyKey() != null) {
      paymentIdsByIdempotencyKey.remove(key(payment.merchantId(), payment.idempotencyKey()),
          payment.id());
    }
    return true;
  }

  @Override
  public synchronized List<Payment> claimDueReversals(Instant now, Instant leaseUntil,
      int limit) {
    List<Payment> claimed = payments.values().stream()
        .filter(payment -> payment.status() == PaymentStatus.PENDING
            && !payment.authorizationInProgress() && payment.nextReversalAt() != null
            && !payment.nextReversalAt().isAfter(now))
        .sorted(Comparator.comparing(Payment::nextReversalAt))
        .limit(limit)
        .map(payment -> payment.withNextReversalAt(leaseUntil))
        .toList();
    claimed.forEach(payment -> payments.put(payment.id(), payment));
    return claimed;
  }

  @Override
  public List<Payment> findCreatedBetween(Instant startInclusive, Instant endExclusive) {
    return payments.values().stream()
        .filter(payment -> !payment.createdAt().isBefore(startInclusive)
            && payment.createdAt().isBefore(endExclusive))
        .toList();
  }

  @Override
  public synchronized int recoverStaleAuthorizations(Instant startedBefore, Instant retryAt) {
    int recovered = 0;
    for (Map.Entry<UUID, Payment> entry : payments.entrySet()) {
      Payment payment = entry.getValue();
      if (payment.authorizationInProgress() && payment.createdAt().isBefore(startedBefore)) {
        entry.setValue(payment.withUnknownOutcome(retryAt));
        recovered++;
      }
    }
    return recovered;
  }

  @Override
  public int purgeExpiredIdempotencyKeys(Instant now) {
    return 0;
  }

  private static String key(String merchantId, String idempotencyKey) {
    return merchantId + "\u0000" + idempotencyKey;
  }
}