package com.checkout.payment.gateway.controller;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.filter.MerchantAuthenticationInterceptor;
import com.checkout.payment.gateway.model.ErrorResponse;
import com.checkout.payment.gateway.model.Payment;
import com.checkout.payment.gateway.model.PaymentResponse;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.service.PaymentGatewayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/payments", "/v1/payments"})
@Tag(name = "Payments")
public class PaymentGatewayController {

  private final PaymentGatewayService paymentGatewayService;

  public PaymentGatewayController(PaymentGatewayService paymentGatewayService) {
    this.paymentGatewayService = paymentGatewayService;
  }

  @PostMapping
  @Operation(summary = "Process a card payment")
  @ApiResponse(responseCode = "201", description = "Payment Authorized or Declined by the bank")
  @ApiResponse(responseCode = "202", description = "Payment Pending: the bank outcome is unknown "
      + "and the authorization is being reversed; poll the Location for the final status")
  @ApiResponse(responseCode = "400", description = "Payment rejected; /v1 returns RFC 7807 "
      + "Problem Details while /payments retains the legacy error body",
      content = @Content(schema = @Schema(oneOf = {ErrorResponse.class, ProblemDetail.class})))
  @ApiResponse(responseCode = "401", description = "Merchant API key missing, invalid, revoked, "
      + "or expired")
  @ApiResponse(responseCode = "409", description = "A request with the same Idempotency-Key is "
      + "still in progress; retry later",
      content = @Content(schema = @Schema(oneOf = {ErrorResponse.class, ProblemDetail.class})))
  @ApiResponse(responseCode = "422", description = "Idempotency-Key already used for a different "
      + "payment", content = @Content(schema = @Schema(oneOf = {ErrorResponse.class, ProblemDetail.class})))
  @ApiResponse(responseCode = "429", description = "Too many payments in progress for this "
      + "merchant; nothing was stored, retry after the Retry-After delay",
      content = @Content(schema = @Schema(oneOf = {ErrorResponse.class, ProblemDetail.class})))
  @ApiResponse(responseCode = "502", description = "Acquiring bank unavailable; no payment created",
      content = @Content(schema = @Schema(oneOf = {ErrorResponse.class, ProblemDetail.class})))
  public ResponseEntity<PaymentResponse> processPayment(
      @Parameter(description = "Unique key (e.g. a UUID) that makes retries safe: a retry with "
          + "the same key returns the original payment instead of charging again")
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestAttribute(MerchantAuthenticationInterceptor.MERCHANT_ID_ATTRIBUTE) String merchantId,
      @Valid @RequestBody PostPaymentRequest request, HttpServletRequest httpRequest) {
    Payment payment = paymentGatewayService.processPayment(request, idempotencyKey, merchantId);
    HttpStatus status =
        payment.status() == PaymentStatus.PENDING ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
    String paymentPath = httpRequest.getRequestURI().startsWith("/v1/")
        ? "/v1/payments/" : "/payments/";
    return ResponseEntity.status(status)
        .location(URI.create(paymentPath + payment.id()))
        .body(PaymentResponse.from(payment));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Retrieve a previously processed payment")
  @ApiResponse(responseCode = "200", description = "Payment found")
  @ApiResponse(responseCode = "404", description = "Payment not found",
      content = @Content(schema = @Schema(oneOf = {ErrorResponse.class, ProblemDetail.class})))
  public PaymentResponse getPayment(@PathVariable UUID id,
      @RequestAttribute(MerchantAuthenticationInterceptor.MERCHANT_ID_ATTRIBUTE) String merchantId) {
    return PaymentResponse.from(paymentGatewayService.getPayment(id, merchantId));
  }
}
