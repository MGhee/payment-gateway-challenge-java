package com.checkout.payment.gateway.exception;

import com.checkout.payment.gateway.model.ErrorResponse;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
  public ResponseEntity<ErrorResponse> handleInvalidRequest(MethodArgumentNotValidException ex) {
    List<String> errors = ex.getBindingResult().getAllErrors().stream()
        .map(CommonExceptionHandler::describe)
        .sorted()
        .toList();
    LOG.info("Payment rejected: {}", errors);
    return ResponseEntity.badRequest()
        .body(ErrorResponse.rejected("Invalid payment request", errors));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleUnreadableRequest(HttpMessageNotReadableException ex) {
    LOG.info("Payment rejected: malformed request body");
    return ResponseEntity.badRequest()
        .body(ErrorResponse.rejected("Malformed request body", null));
  }

  @ExceptionHandler({PaymentNotFoundException.class, MethodArgumentTypeMismatchException.class})
  public ResponseEntity<ErrorResponse> handleNotFound(Exception ex) {
    LOG.info("Payment not found: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ErrorResponse.of("Payment not found"));
  }

  @ExceptionHandler(AcquiringBankException.class)
  public ResponseEntity<ErrorResponse> handleBankFailure(AcquiringBankException ex) {
    LOG.error("Payment failed: acquiring bank unavailable", ex);
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(ErrorResponse.of("Acquiring bank unavailable, please retry later"));
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException ex) {
    LOG.info("Duplicate request rejected: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorResponse.of("A request with this Idempotency-Key is still being processed"));
  }

  @ExceptionHandler(IdempotencyKeyReusedException.class)
  public ResponseEntity<ErrorResponse> handleIdempotencyKeyReused(IdempotencyKeyReusedException ex) {
    LOG.info("Duplicate request rejected: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .body(ErrorResponse.of("This Idempotency-Key was already used for a different payment"));
  }

  // Field errors use the JSON property names the merchant actually sent
  private static String describe(ObjectError error) {
    if (error instanceof FieldError fieldError) {
      return SNAKE_CASE.translate(fieldError.getField()) + " " + fieldError.getDefaultMessage();
    }
    return error.getDefaultMessage();
  }
}
