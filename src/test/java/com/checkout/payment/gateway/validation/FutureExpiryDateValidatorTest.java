package com.checkout.payment.gateway.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.checkout.payment.gateway.model.PostPaymentRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FutureExpiryDateValidatorTest {

  private final FutureExpiryDateValidator validator = new FutureExpiryDateValidator(
      Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), ZoneOffset.UTC));

  @ParameterizedTest(name = "{0}/{1} valid: {2}")
  @CsvSource({
      "10, 2026, true",
      "11, 2026, true",
      "1, 2027, true",
      "9, 2026, false",
      "12, 2025, false"})
  void acceptsExpiryDatesFromTheCurrentMonthOnwards(int month, int year, boolean valid) {
    assertThat(validator.isValid(requestExpiring(month, year), null)).isEqualTo(valid);
  }

  @Test
  void leavesMissingOrOutOfRangeFieldsToTheFieldConstraints() {
    assertThat(validator.isValid(requestExpiring(null, 2020), null)).isTrue();
    assertThat(validator.isValid(requestExpiring(13, 2020), null)).isTrue();
    assertThat(validator.isValid(requestExpiring(1, null), null)).isTrue();
  }

  private static PostPaymentRequest requestExpiring(Integer month, Integer year) {
    return new PostPaymentRequest("2222405343248877", month, year, "GBP", 100, "123");
  }
}
