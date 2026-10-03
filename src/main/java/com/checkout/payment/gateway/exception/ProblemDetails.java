package com.checkout.payment.gateway.exception;

import com.checkout.payment.gateway.model.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

public final class ProblemDetails {

  private ProblemDetails() {
  }

  public static ResponseEntity<Object> response(HttpStatus status, ErrorResponse legacyBody,
      String type, String detail, List<String> errors, HttpServletRequest request) {
    if (!usesProblemDetails(request)) {
      return ResponseEntity.status(status).body(legacyBody);
    }
    ProblemDetail problem = create(status, type, detail, request);
    if (errors != null) {
      problem.setProperty("errors", errors);
    }
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
  }

  public static ProblemDetail create(HttpStatus status, String type, String detail,
      HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create("urn:payment-gateway:problem:" + type));
    problem.setTitle(status.getReasonPhrase());
    problem.setInstance(URI.create(request.getRequestURI()));
    return problem;
  }

  public static boolean usesProblemDetails(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path.startsWith("/v1/payments") || path.startsWith("/admin/");
  }

  public static void writeUnauthorized(HttpServletRequest request, HttpServletResponse response,
      ObjectMapper objectMapper, String detail) throws IOException {
    HttpStatus status = HttpStatus.UNAUTHORIZED;
    response.setStatus(status.value());
    response.setHeader("WWW-Authenticate", "ApiKey realm=\"payments\"");
    if (usesProblemDetails(request)) {
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      objectMapper.writeValue(response.getOutputStream(),
          create(status, "authentication-required", detail, request));
      return;
    }
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(detail));
  }
}