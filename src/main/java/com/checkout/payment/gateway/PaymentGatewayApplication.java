package com.checkout.payment.gateway;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@OpenAPIDefinition(info = @Info(title = "Payment Gateway API", version = "v1",
    description = "Lets merchants process card payments and retrieve their details"))
public class PaymentGatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(PaymentGatewayApplication.class, args);
  }

}
