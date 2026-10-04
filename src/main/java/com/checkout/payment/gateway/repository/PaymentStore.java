package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.model.Payment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentStore {

  Optional<Payment> addIfKeyUnused(Payment payment);

  // Compare-and-set: writes next only if status, in-flight flag and attempts still match current
  boolean transition(Payment current, Payment next);

  Optional<Payment> get(UUID id);

  Optional<Payment> get(UUID id, String merchantId);

  Optional<Payment> getByIdempotencyKey(String merchantId, String idempotencyKey);

  // Deletes the payment only while it is still waiting for its own bank response
  boolean removeInFlight(Payment payment);

  // Leases due reversals until leaseUntil, so concurrent workers never pick the same payment
  List<Payment> claimDueReversals(Instant now, Instant leaseUntil, int limit);

  List<Payment> findCreatedBetween(Instant startInclusive, Instant endExclusive);

  int recoverStaleAuthorizations(Instant startedBefore, Instant retryAt);

  int purgeExpiredIdempotencyKeys(Instant now);
}