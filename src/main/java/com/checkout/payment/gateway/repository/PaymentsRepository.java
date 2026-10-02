package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.model.Payment;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentsRepository {

  private final Map<UUID, Payment> payments = new ConcurrentHashMap<>();
  private final Map<String, UUID> paymentIdsByIdempotencyKey = new ConcurrentHashMap<>();

  public void save(Payment payment) {
    payments.put(payment.id(), payment);
  }

  // Atomic so two concurrent requests with the same key can't both create a payment
  public synchronized Optional<Payment> addIfKeyUnused(Payment payment) {
    if (payment.idempotencyKey() != null) {
      UUID originalId = paymentIdsByIdempotencyKey.putIfAbsent(payment.idempotencyKey(), payment.id());
      if (originalId != null) {
        return Optional.of(payments.get(originalId));
      }
    }
    payments.put(payment.id(), payment);
    return Optional.empty();
  }

  public Optional<Payment> get(UUID id) {
    return Optional.ofNullable(payments.get(id));
  }

  public synchronized void remove(Payment payment) {
    payments.remove(payment.id());
    if (payment.idempotencyKey() != null) {
      paymentIdsByIdempotencyKey.remove(payment.idempotencyKey(), payment.id());
    }
  }
}
