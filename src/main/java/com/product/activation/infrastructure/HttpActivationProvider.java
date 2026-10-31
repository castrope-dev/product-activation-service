package com.product.activation.infrastructure;

import com.product.activation.application.result.*;

import com.product.activation.application.ActivationProvider;
import com.product.activation.domain.ActivationRequest;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

import java.net.http.HttpClient;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component
@Slf4j
public class HttpActivationProvider implements ActivationProvider {
    private final RestClient client;
    private final MeterRegistry metrics;

    public HttpActivationProvider(RestClient.Builder builder, MeterRegistry metrics,
                                  @Value("${activation.provider.url}") String url,
                                  @Value("${activation.provider.token:}") String token,
                                  @Value("${activation.provider.connect-timeout:2s}") Duration connectTimeout,
                                  @Value("${activation.provider.read-timeout:5s}") Duration readTimeout,
                                  @Value("${activation.worker.lease:30s}") Duration lease) {
        if (lease.compareTo(connectTimeout.plus(readTimeout).plusSeconds(5)) <= 0)
            throw new IllegalArgumentException("Worker lease must exceed HTTP timeouts plus 5 seconds");
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(readTimeout);
        var configured = builder.baseUrl(url).requestFactory(factory);
        if (!token.isBlank()) configured.defaultHeader("Authorization", "Bearer " + token);
        this.client = configured.build();
        this.metrics = metrics;
    }

    @Override
    public Outcome activate(ActivationRequest request) {
        long started = System.nanoTime();
        var sample = Timer.start(metrics);
        Outcome result;
        try {
            result = client.post().uri("/provider/v1/activate")
                    .header("Idempotency-Key", request.getId().toString())
                    .header("X-Correlation-ID", request.getCorrelationId())
                    .body(Map.of("externalId", request.getExternalId(), "customerId", request.getCustomerId(),
                            "productCode", request.getProductCode()))
                    .exchange((sent, response) -> {
                        int status = response.getStatusCode().value();
                        if (status >= 200 && status < 300) return new Success();
                        if (status == 429 || status >= 500) return new TemporaryFailure("PROVIDER_" + status,
                                retryAfter(response.getHeaders().getFirst("Retry-After"), Instant.now()));
                        return new Rejected("PROVIDER_" + status);
                    });
        } catch (ResourceAccessException exception) {
            log.atWarn().addKeyValue("requestId", request.getId())
                    .addKeyValue("errorType", exception.getClass().getName())
                    .addKeyValue("causeType", exception.getMostSpecificCause().getClass().getName())
                    .log("provider_connection_failed");
            result = new TemporaryFailure("PROVIDER_UNAVAILABLE", null);
        } catch (RestClientException exception) {
            log.atWarn().addKeyValue("requestId", request.getId())
                    .addKeyValue("errorType", exception.getClass().getName()).log("provider_protocol_failed");
            result = new TemporaryFailure("PROVIDER_PROTOCOL_ERROR", null);
        }
        String outcome = switch (result) {
            case Success ignored -> "success";
            case Rejected ignored -> "rejected";
            case TemporaryFailure ignored -> "temporary_failure";
        };
        sample.stop(metrics.timer("activation.provider.duration", "outcome", outcome));
        metrics.counter("activation.provider.calls", "outcome", outcome).increment();
        var event = result instanceof Success ? log.atInfo() : log.atWarn();
        event.addKeyValue("requestId", request.getId()).addKeyValue("attempt", request.getAttempts())
                .addKeyValue("outcome", outcome)
                .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000)
                .addKeyValue("failureCode", switch (result) {
                    case Success ignored -> null;
                    case Rejected rejected -> rejected.code();
                    case TemporaryFailure failure -> failure.code();
                }).log("provider_call_finished");
        return result;
    }

    static Duration retryAfter(String value, Instant now) {
        if (value == null) return null;
        try {
            Duration duration = value.matches("\\d+") ? Duration.ofSeconds(Long.parseLong(value))
                    : Duration.between(now, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            if (duration.isNegative()) return Duration.ZERO;
            return duration.compareTo(Duration.ofDays(1)) > 0 ? Duration.ofDays(1) : duration;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
