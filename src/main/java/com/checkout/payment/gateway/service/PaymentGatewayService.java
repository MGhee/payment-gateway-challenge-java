package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.AcquiringBankException;
import com.checkout.payment.gateway.exception.BankOutcomeUnknownException;
import com.checkout.payment.gateway.exception.IdempotencyConflictException;
import com.checkout.payment.gateway.exception.IdempotencyKeyReusedException;
import com.checkout.payment.gateway.exception.PaymentNotFoundException;
import com.checkout.payment.gateway.model.Payment;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaymentGatewayService {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentGatewayService.class);

  private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
  private final PaymentsRepository paymentsRepository;
  private final BankClient bankClient;
  private final PaymentReversalJob reversalJob;
  private final MeterRegistry meterRegistry;

  public PaymentGatewayService(PaymentsRepository paymentsRepository, BankClient bankClient,
      PaymentReversalJob reversalJob, MeterRegistry meterRegistry) {
    this.paymentsRepository = paymentsRepository;
    this.bankClient = bankClient;
    this.reversalJob = reversalJob;
    this.meterRegistry = meterRegistry;
  }

  public Payment processPayment(PostPaymentRequest request, String idempotencyKey) {
    // Stored before calling the bank, so a payment the bank may have authorized is never lost
    Payment pending = Payment.pending(request, idempotencyKey);
    inFlight.add(pending.id());
    try {
      Optional<Payment> original = paymentsRepository.addIfKeyUnused(pending);
      if (original.isPresent()) {
        return replay(original.get(), request);
      }
      return authorize(pending, request);
    } finally {
      inFlight.remove(pending.id());
    }
  }

  private Payment replay(Payment original, PostPaymentRequest request) {
    if (!original.matches(request)) {
      throw new IdempotencyKeyReusedException(original.id());
    }
    if (inFlight.contains(original.id())) {
      throw new IdempotencyConflictException(original.id());
    }
    LOG.info("Payment {} returned for a retried request", original.id());
    return original;
  }

  private Payment authorize(Payment pending, PostPaymentRequest request) {
    Payment payment;
    try {
      boolean authorized = bankClient.authorize(pending.id(), request).authorized();
      payment = pending.withStatus(authorized ? PaymentStatus.AUTHORIZED : PaymentStatus.DECLINED);
      paymentsRepository.save(payment);
    } catch (AcquiringBankException e) {
      paymentsRepository.remove(pending);
      throw e;
    } catch (BankOutcomeUnknownException e) {
      LOG.warn("Bank outcome unknown for payment {}, scheduling reversal", pending.id(), e);
      reversalJob.schedule(pending.id());
      payment = pending;
    }

    meterRegistry.counter("payments.processed", "status", payment.status().getName()).increment();
    LOG.info("Payment {} {}: {} {}", payment.id(), payment.status().getName(), payment.amount(),
        payment.currency());
    return payment;
  }

  public Payment getPayment(UUID id) {
    return paymentsRepository.get(id).orElseThrow(() -> new PaymentNotFoundException(id));
  }
}
