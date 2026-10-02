package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.PaymentNotFoundException;
import com.checkout.payment.gateway.model.Payment;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaymentGatewayService {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentGatewayService.class);

  private final PaymentsRepository paymentsRepository;
  private final BankClient bankClient;

  public PaymentGatewayService(PaymentsRepository paymentsRepository, BankClient bankClient) {
    this.paymentsRepository = paymentsRepository;
    this.bankClient = bankClient;
  }

  public Payment processPayment(PostPaymentRequest request) {
    boolean authorized = bankClient.authorize(request).authorized();
    PaymentStatus status = authorized ? PaymentStatus.AUTHORIZED : PaymentStatus.DECLINED;

    Payment payment = new Payment(
        UUID.randomUUID(),
        status,
        request.cardNumberLastFour(),
        request.expiryMonth(),
        request.expiryYear(),
        request.currency(),
        request.amount());
    paymentsRepository.add(payment);

    LOG.info("Payment {} {}: {} {}", payment.id(), status.getName(), payment.amount(),
        payment.currency());
    return payment;
  }

  public Payment getPayment(UUID id) {
    return paymentsRepository.get(id).orElseThrow(() -> new PaymentNotFoundException(id));
  }
}
