package com.checkout.payment.gateway.controller;

import com.checkout.payment.gateway.model.ApiKeyCredential;
import com.checkout.payment.gateway.service.MerchantCredentialsService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/merchants/{merchantId}/api-keys")
@SecurityRequirement(name = "GatewayAdminKey")
public class MerchantCredentialsController {

  private final MerchantCredentialsService credentialsService;

  public MerchantCredentialsController(MerchantCredentialsService credentialsService) {
    this.credentialsService = credentialsService;
  }

  @PostMapping
  public ResponseEntity<ApiKeyCredential> provision(@PathVariable String merchantId) {
    ApiKeyCredential credential = credentialsService.provision(merchantId);
    return ResponseEntity.created(URI.create("/admin/merchants/" + merchantId + "/api-keys/"
        + credential.keyId())).body(credential);
  }

  @PostMapping("/{keyId}/rotate")
  public ApiKeyCredential rotate(@PathVariable String merchantId, @PathVariable UUID keyId) {
    return credentialsService.rotate(merchantId, keyId);
  }

  @DeleteMapping("/{keyId}")
  public ResponseEntity<Void> revoke(@PathVariable String merchantId, @PathVariable UUID keyId) {
    credentialsService.revoke(merchantId, keyId);
    return ResponseEntity.noContent().build();
  }
}