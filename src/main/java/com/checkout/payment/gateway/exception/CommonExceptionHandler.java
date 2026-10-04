package com.checkout.payment.gateway.exception;

import com.checkout.payment.gateway.model.ErrorResponse;
import com.checkout.payment.gateway.service.MerchantCredentialNotFoundException;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class CommonExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(CommonExceptionHandler.class);
  private static final PropertyNamingStrategies.SnakeCaseStrategy SNAKE_CASE =
      new PropertyNamingStrategies.SnakeCaseStrategy();

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Object> handleInvalidRequest(MethodArgumentNotValidException ex,
      HttpServletRequest request) {
    List<String> errors = ex.getBindingResult().getAllErrors().stream()
        .map(CommonExceptionHandler::describe)
        .sorted()
        .toList();
    LOG.info("Payment rejected: {}", errors);
    return ProblemDetails.response(HttpStatus.BAD_REQUEST,
        ErrorResponse.rejected("Invalid payment request", errors), "invalid-payment-request",
        "Invalid payment request", errors, request);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Object> handleUnreadableRequest(HttpMessageNotReadableException ex,
      HttpServletRequest request) {
    LOG.info("Payment rejected: malformed request body");
    return ProblemDetails.response(HttpStatus.BAD_REQUEST,
        ErrorResponse.rejected("Malformed request body", null), "malformed-request",
        "Malformed request body", null, request);
  }

  @ExceptionHandler({PaymentNotFoundException.class, MerchantCredentialNotFoundException.class,
      MethodArgumentTypeMismatchException.class})
  public ResponseEntity<Object> handleNotFound(Exception ex, HttpServletRequest request) {
    LOG.info("Payment not found: {}", ex.getMessage());
    String detail = ex instanceof MerchantCredentialNotFoundException
        ? "Merchant API key not found" : "Payment not found";
    return ProblemDetails.response(HttpStatus.NOT_FOUND, ErrorResponse.of(detail), "not-found",
        detail, null, request);
  }

  @ExceptionHandler(AcquiringBankException.class)
  public ResponseEntity<Object> handleBankFailure(AcquiringBankException ex,
      HttpServletRequest request) {
    LOG.error("Payment failed: acquiring bank unavailable", ex);
    String detail = "Acquiring bank unavailable, please retry later";
    return ProblemDetails.response(HttpStatus.BAD_GATEWAY, ErrorResponse.of(detail),
        "acquiring-bank-unavailable", detail, null, request);
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  public ResponseEntity<Object> handleIdempotencyConflict(IdempotencyConflictException ex,
      HttpServletRequest request) {
    LOG.info("Duplicate request rejected: {}", ex.getMessage());
    String detail = "A request with this Idempotency-Key is still being processed";
    return ProblemDetails.response(HttpStatus.CONFLICT, ErrorResponse.of(detail),
        "idempotency-request-in-progress", detail, null, request);
  }

  @ExceptionHandler(IdempotencyKeyReusedException.class)
  public ResponseEntity<Object> handleIdempotencyKeyReused(IdempotencyKeyReusedException ex,
      HttpServletRequest request) {
    LOG.info("Duplicate request rejected: {}", ex.getMessage());
    String detail = "This Idempotency-Key was already used for a different payment";
    return ProblemDetails.response(HttpStatus.UNPROCESSABLE_ENTITY, ErrorResponse.of(detail),
        "idempotency-key-reused", detail, null, request);
  }

  @ExceptionHandler(TooManyConcurrentPaymentsException.class)
  public ResponseEntity<Object> handleTooManyConcurrentPayments(
      TooManyConcurrentPaymentsException ex, HttpServletRequest request) {
    LOG.warn("Payment rejected: {}", ex.getMessage());
    String detail = "Too many payments in progress for this merchant, please retry shortly";
    ResponseEntity<Object> response = ProblemDetails.response(HttpStatus.TOO_MANY_REQUESTS,
        ErrorResponse.of(detail), "too-many-concurrent-payments", detail, null, request);
    return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
        .header(HttpHeaders.RETRY_AFTER, "1").body(response.getBody());
  }

  // Field errors use the JSON property names the merchant actually sent
  private static String describe(ObjectError error) {
    if (error == null) {
      return "Invalid request";
    }
    if (error instanceof FieldError fieldError) {
      return SNAKE_CASE.translate(fieldError.getField()) + " " + fieldError.getDefaultMessage();
    }
    String message = error.getDefaultMessage();
    return message == null ? "Invalid request" : message;
  }
}
