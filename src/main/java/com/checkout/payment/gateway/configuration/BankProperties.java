package com.checkout.payment.gateway.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bank")
public record BankProperties(String url, Duration connectTimeout, Duration readTimeout) {

}
