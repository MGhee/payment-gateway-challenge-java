package com.checkout.payment.gateway.filter;

import com.checkout.payment.gateway.exception.ProblemDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Component
public class GatewayAdminAuthenticationInterceptor implements HandlerInterceptor, WebMvcConfigurer {

  public static final String ADMIN_KEY_HEADER = "X-Gateway-Admin-Key";

  private final String configuredKey;
  private final ObjectMapper objectMapper;

  public GatewayAdminAuthenticationInterceptor(
      @Value("${gateway.admin-api-key:}") String configuredKey, ObjectMapper objectMapper) {
    this.configuredKey = configuredKey;
    this.objectMapper = objectMapper;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this).addPathPatterns("/admin/**");
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
      Object handler) throws IOException {
    String suppliedKey = request.getHeader(ADMIN_KEY_HEADER);
    byte[] supplied = suppliedKey == null ? new byte[0]
        : suppliedKey.getBytes(StandardCharsets.UTF_8);
    byte[] expected = configuredKey.getBytes(StandardCharsets.UTF_8);
    if (!configuredKey.isBlank() && MessageDigest.isEqual(supplied, expected)) {
      return true;
    }
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setHeader("WWW-Authenticate", "AdminKey realm=\"gateway-admin\"");
    response.setContentType("application/problem+json");
    objectMapper.writeValue(response.getOutputStream(), ProblemDetails.create(
      org.springframework.http.HttpStatus.UNAUTHORIZED, "admin-authentication-required",
      "Gateway administrator authentication required", request));
    return false;
  }
}