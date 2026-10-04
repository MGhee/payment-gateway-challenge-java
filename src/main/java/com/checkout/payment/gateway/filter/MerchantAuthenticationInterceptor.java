package com.checkout.payment.gateway.filter;

import com.checkout.payment.gateway.exception.ProblemDetails;
import com.checkout.payment.gateway.service.MerchantCredentialsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Component
public class MerchantAuthenticationInterceptor implements HandlerInterceptor, WebMvcConfigurer {

  public static final String API_KEY_HEADER = "X-API-Key";
  public static final String MERCHANT_ID_ATTRIBUTE = "merchantId";

  private final MerchantCredentialsService credentialsService;
  private final ObjectMapper objectMapper;

  public MerchantAuthenticationInterceptor(MerchantCredentialsService credentialsService,
      ObjectMapper objectMapper) {
    this.credentialsService = credentialsService;
    this.objectMapper = objectMapper;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this).addPathPatterns(
      "/payments", "/payments/**", "/v1/payments", "/v1/payments/**");
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
      Object handler) throws IOException {
    String merchantId = credentialsService.authenticate(request.getHeader(API_KEY_HEADER));
    if (merchantId == null) {
      ProblemDetails.writeUnauthorized(request, response, objectMapper, "Authentication required");
      return false;
    }
    request.setAttribute(MERCHANT_ID_ATTRIBUTE, merchantId);
    return true;
  }

}