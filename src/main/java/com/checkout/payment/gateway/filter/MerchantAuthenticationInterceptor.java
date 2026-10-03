package com.checkout.payment.gateway.filter;

import com.checkout.payment.gateway.model.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Component
public class MerchantAuthenticationInterceptor implements HandlerInterceptor, WebMvcConfigurer {

  public static final String API_KEY_HEADER = "X-API-Key";
  public static final String MERCHANT_ID_ATTRIBUTE = "merchantId";

  private final Map<String, String> apiKeysByMerchant;
  private final ObjectMapper objectMapper;

  public MerchantAuthenticationInterceptor(
      @Value("${gateway.merchant-api-keys:}") String configuredKeys,
      ObjectMapper objectMapper) {
    this.apiKeysByMerchant = parseKeys(configuredKeys);
    this.objectMapper = objectMapper;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this).addPathPatterns("/payments", "/payments/**");
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
      Object handler) throws IOException {
    byte[] suppliedKey = request.getHeader(API_KEY_HEADER) == null ? new byte[0]
        : request.getHeader(API_KEY_HEADER).getBytes(StandardCharsets.UTF_8);
    String merchantId = null;
    for (Map.Entry<String, String> configured : apiKeysByMerchant.entrySet()) {
      if (MessageDigest.isEqual(suppliedKey,
          configured.getValue().getBytes(StandardCharsets.UTF_8))) {
        merchantId = configured.getKey();
      }
    }
    if (merchantId == null) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setHeader("WWW-Authenticate", "ApiKey realm=\"payments\"");
      response.setContentType("application/json");
      objectMapper.writeValue(response.getOutputStream(),
          ErrorResponse.of("Authentication required"));
      return false;
    }
    request.setAttribute(MERCHANT_ID_ATTRIBUTE, merchantId);
    return true;
  }

  private static Map<String, String> parseKeys(String configuredKeys) {
    Map<String, String> keys = new LinkedHashMap<>();
    for (String entry : configuredKeys.split(",")) {
      if (entry.isBlank()) {
        continue;
      }
      int separator = entry.indexOf('=');
      if (separator < 1 || separator == entry.length() - 1) {
        throw new IllegalArgumentException(
            "MERCHANT_API_KEYS must be comma-separated merchant-id=api-key pairs");
      }
      String merchantId = entry.substring(0, separator).trim();
      String apiKey = entry.substring(separator + 1).trim();
      if (merchantId.isEmpty() || apiKey.isEmpty() || keys.putIfAbsent(merchantId, apiKey) != null) {
        throw new IllegalArgumentException("Merchant ids and API keys must be non-empty and unique");
      }
    }
    if (new HashSet<>(keys.values()).size() != keys.size()) {
      throw new IllegalArgumentException("Each merchant must have a distinct API key");
    }
    return Map.copyOf(keys);
  }
}