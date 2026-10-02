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

  public void save(Payment payment) {
    payments.put(payment.id(), payment);
  }

  public Optional<Payment> get(UUID id) {
    return Optional.ofNullable(payments.get(id));
  }

  public void remove(UUID id) {
    payments.remove(id);
  }
}
