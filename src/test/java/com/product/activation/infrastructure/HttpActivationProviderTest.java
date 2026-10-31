package com.product.activation.infrastructure;

import com.product.activation.application.result.*;

import com.product.activation.domain.ActivationRequest;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class HttpActivationProviderTest {
    HttpServer server;
    ExecutorService executor;
    HttpActivationProvider provider;
    ActivationRequest request = new ActivationRequest("e", "c", "p", "owner", "trace-123", Instant.now());

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        provider = new HttpActivationProvider(RestClient.builder(), new SimpleMeterRegistry(),
                "http://localhost:" + server.getAddress().getPort(), "", Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(30));
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void responds(int status, long delay, String retryAfter, AtomicReference<String> key) {
        server.createContext("/provider/v1/activate", exchange -> {
            key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.getRequestBody());
            assertThat(body.get("externalId").asText()).isEqualTo(request.getExternalId());
            assertThat(body.get("customerId").asText()).isEqualTo(request.getCustomerId());
            assertThat(body.get("productCode").asText()).isEqualTo(request.getProductCode());
            try {
                Thread.sleep(delay);
                if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
                exchange.sendResponseHeaders(status, -1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
    }

    @Test
    void successPropagatesStableIdempotencyKey() {
        var key = new AtomicReference<String>();
        responds(204, 0, null, key);
        assertThat(provider.activate(request)).isInstanceOf(Success.class);
        assertThat(provider.activate(request)).isInstanceOf(Success.class);
        assertThat(key.get()).isEqualTo(request.getId().toString());
    }

    @Test
    void badRequestIsPermanent() {
        responds(400, 0, null, new AtomicReference<>());
        assertThat(provider.activate(request)).isEqualTo(new Rejected("PROVIDER_400"));
    }

    @Test
    void serverFailureIsRetryable() {
        responds(500, 0, null, new AtomicReference<>());
        assertThat(provider.activate(request)).isEqualTo(new TemporaryFailure("PROVIDER_500", null));
    }

    @Test
    void timeoutIsRetryable() {
        responds(200, 500, null, new AtomicReference<>());
        assertThat(provider.activate(request)).isEqualTo(new TemporaryFailure("PROVIDER_UNAVAILABLE", null));
    }

    @Test
    void honorsRetryAfter() {
        responds(429, 0, "30", new AtomicReference<>());
        assertThat(provider.activate(request)).isEqualTo(new TemporaryFailure("PROVIDER_429", Duration.ofSeconds(30)));
        assertThat(HttpActivationProvider.retryAfter("Wed, 01 Jan 2025 00:01:00 GMT", Instant.parse("2025-01-01T00:00:00Z")))
                .isEqualTo(Duration.ofMinutes(1));
        assertThat(HttpActivationProvider.retryAfter("invalid", Instant.now())).isNull();
    }
}
