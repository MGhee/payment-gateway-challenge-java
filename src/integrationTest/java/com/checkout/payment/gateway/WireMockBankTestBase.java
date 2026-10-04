package com.checkout.payment.gateway;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Real sockets against a fault-injecting bank, which the Mountebank simulator cannot do
abstract class WireMockBankTestBase extends IntegrationTestBase {

  static final String CARD = "2222405343248877";
  static final String AUTHORIZED = """
      {"authorized": true, "authorization_code": "auth-code"}
      """;

  static final WireMockServer BANK = new WireMockServer(options().dynamicPort());

  static {
    BANK.start();
  }

  @DynamicPropertySource
  static void bankUrl(DynamicPropertyRegistry registry) {
    // localhost resolves to several addresses, like a load-balanced bank host
    registry.add("bank.url", () -> "http://localhost:" + BANK.port());
  }

  @BeforeEach
  void resetBank() {
    BANK.resetAll();
  }

  // Releases every task at the same instant to maximise contention
  static <T> List<T> concurrently(int count, Callable<T> task) throws Exception {
    ExecutorService threads = Executors.newFixedThreadPool(count);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<T>> pending = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        pending.add(threads.submit(() -> {
          start.await();
          return task.call();
        }));
      }
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> result : pending) {
        results.add(result.get(60, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      threads.shutdownNow();
    }
  }
}
