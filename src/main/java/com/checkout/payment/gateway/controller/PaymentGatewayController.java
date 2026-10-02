package com.checkout.payment.gateway.controller;

import com.checkout.payment.gateway.model.Payment;
import com.checkout.payment.gateway.model.PaymentResponse;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.service.PaymentGatewayService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentGatewayController {

  private final PaymentGatewayService paymentGatewayService;

  public PaymentGatewayController(PaymentGatewayService paymentGatewayService) {
    this.paymentGatewayService = paymentGatewayService;
  }

  @PostMapping
  public ResponseEntity<PaymentResponse> processPayment(
      @Valid @RequestBody PostPaymentRequest request) {
    Payment payment = paymentGatewayService.processPayment(request);
    return ResponseEntity.created(URI.create("/payments/" + payment.id()))
        .body(PaymentResponse.from(payment));
  }

  @GetMapping("/{id}")
  public PaymentResponse getPayment(@PathVariable UUID id) {
    return PaymentResponse.from(paymentGatewayService.getPayment(id));
  }
}
