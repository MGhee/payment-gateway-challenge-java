package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.AcquiringBankException;
import com.checkout.payment.gateway.exception.BankOutcomeUnknownException;
import com.checkout.payment.gateway.exception.IdempotencyConflictException;
import com.checkout.payment.gateway.exception.IdempotencyKeyReusedException;
import com.checkout.payment.gateway.exception.PaymentNotFoundException;
import com.checkout.payment.gateway.exception.TooManyConcurrentPaymentsException;
import com.checkout.payment.gateway.model.Payment;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.repository.PaymentStore;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
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
  private final PaymentStore paymentsRepository;
  private final BankClient bankClient;
  private final MeterRegistry meterRegistry;
  private final BulkheadRegistry merchantBulkheads;

  public PaymentGatewayService(PaymentStore paymentsRepository, BankClient bankClient,
      MeterRegistry meterRegistry, BulkheadRegistry merchantBulkheads) {
    this.paymentsRepository = paymentsRepository;
    this.bankClient = bankClient;
    this.meterRegistry = meterRegistry;
    this.merchantBulkheads = merchantBulkheads;
  }

  public Payment processPayment(PostPaymentRequest request, String idempotencyKey,
      String merchantId) {
    // Per merchant, so one merchant's burst cannot take every connection to the bank
    Bulkhead merchantLimit = merchantBulkheads.bulkhead(merchantId);
    if (!merchantLimit.tryAcquirePermission()) {
      throw new TooManyConcurrentPaymentsException(merchantId);
    }
    try {
      return processWithinLimit(request, idempotencyKey, merchantId);
    } finally {
      merchantLimit.onComplete();
    }
  }

  private Payment processWithinLimit(PostPaymentRequest request, String idempotencyKey,
      String merchantId) {
    // Stored before calling the bank, so a payment the bank may have authorized is never lost
    Payment pending = Payment.pending(request, idempotencyKey, merchantId);
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
    if (original.authorizationInProgress() || inFlight.contains(original.id())) {
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
    } catch (AcquiringBankException e) {
      if (paymentsRepository.removeInFlight(pending)) {
        throw e;
      }
      return takenOverByRecovery(pending);
    } catch (BankOutcomeUnknownException e) {
      LOG.warn("Bank outcome unknown for payment {}, scheduling reversal", pending.id(), e);
      payment = pending.withUnknownOutcome(Instant.now());
    }
    if (!paymentsRepository.transition(pending, payment)) {
      return takenOverByRecovery(pending);
    }
    return recordOutcome(payment);
  }

  // The recovery job judged this authorization stuck and queued a reversal; that decision stands
  private Payment takenOverByRecovery(Payment pending) {
    Payment current = paymentsRepository.get(pending.id()).orElseThrow();
    LOG.warn("Payment {} was handed to the reversal job before the bank answered", pending.id());
    return recordOutcome(current);
  }

  private Payment recordOutcome(Payment payment) {
    meterRegistry.counter("payments.processed", "status", payment.status().getName()).increment();
    LOG.info("Payment {} {}: {} {}", payment.id(), payment.status().getName(), payment.amount(),
        payment.currency());
    return payment;
  }

  public Payment getPayment(UUID id, String merchantId) {
    return paymentsRepository.get(id, merchantId)
        .orElseThrow(() -> new PaymentNotFoundException(id));
  }
}
