package com.checkout.payment.gateway.validation;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Clock;
import java.time.YearMonth;

public class FutureExpiryDateValidator
    implements ConstraintValidator<FutureExpiryDate, PostPaymentRequest> {

  private final Clock clock;

  public FutureExpiryDateValidator(Clock clock) {
    this.clock = clock;
  }

  // A card stays valid until the end of its expiry month, so the current month is accepted
  @Override
  public boolean isValid(PostPaymentRequest request, ConstraintValidatorContext context) {
    Integer month = request.expiryMonth();
    Integer year = request.expiryYear();
    if (month == null || year == null || month < 1 || month > 12) {
      return true; // already reported by the field constraints
    }
    YearMonth now = YearMonth.now(clock);
    return year > now.getYear() || (year == now.getYear() && month >= now.getMonthValue());
  }
}
