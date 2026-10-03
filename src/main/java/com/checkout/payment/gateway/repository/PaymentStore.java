package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.model.Payment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentStore {

  Optional<Payment> addIfKeyUnused(Payment payment);

  void save(Payment payment);

  Optional<Payment> get(UUID id);

  Optional<Payment> get(UUID id, String merchantId);

  Optional<Payment> getByIdempotencyKey(String merchantId, String idempotencyKey);

  void remove(Payment payment);

  List<Payment> findDueReversals(Instant now);

  List<Payment> findCreatedBetween(Instant startInclusive, Instant endExclusive);

  int recoverStaleAuthorizations(Instant startedBefore, Instant retryAt);

  int purgeExpiredIdempotencyKeys(Instant now);
}