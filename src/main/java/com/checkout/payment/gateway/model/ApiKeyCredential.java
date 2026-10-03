package com.checkout.payment.gateway.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ApiKeyCredential(UUID keyId, String apiKey, String keyPrefix, Instant expiresAt) {
}