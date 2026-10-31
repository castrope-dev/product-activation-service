package com.product.activation.application;

import com.product.activation.application.result.*;

import com.product.activation.domain.*;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.concurrent.ThreadLocalRandom;

@Component
@Slf4j
@ConditionalOnProperty(name = "activation.worker.enabled", havingValue = "true", matchIfMissing = true)
public class ActivationWorker {
    private final ActivationRepository repository;
    private final TransactionTemplate transactions;
    private final ActivationProvider provider;
    private final MeterRegistry metrics;
    private final int maxAttempts;
    private final Duration lease;

    public ActivationWorker(ActivationRepository repository, TransactionTemplate transactions,
                            ActivationProvider provider, MeterRegistry metrics,
                            @Value("${activation.worker.max-attempts:5}") int maxAttempts,
                            @Value("${activation.worker.lease:30s}") Duration lease) {
        if (maxAttempts < 1 || lease.isNegative() || lease.isZero())
            throw new IllegalArgumentException("Invalid worker limits");
        this.repository = repository;
        this.transactions = transactions;
        this.provider = provider;
        this.metrics = metrics;
        this.maxAttempts = maxAttempts;
        this.lease = lease;
    }

    @Scheduled(fixedDelayString = "${activation.worker.poll-ms:500}")
    public void poll() {
        try {
            processOne();
        } catch (Exception error) {
            log.atError().addKeyValue("errorType", error.getClass().getName())
                    .addKeyValue("stackFrames", java.util.Arrays.toString(error.getStackTrace()))
                    .log("activation_poll_failed");
        }
    }

    public boolean processOne() {
        ActivationRequest request = transactions.execute(tx -> {
            var now = Instant.now();
            var candidate = repository.lockNext(now).orElse(null);
            if (candidate == null) return null;
            if (candidate.getAttempts() >= maxAttempts) {
                log.atWarn().addKeyValue("requestId", candidate.getId())
                        .addKeyValue("correlationId", candidate.getCorrelationId())
                        .addKeyValue("attempt", candidate.getAttempts()).log("activation_attempt_limit_reached");
                candidate.fail("ATTEMPTS_EXHAUSTED", now);
                return repository.saveAndFlush(candidate);
            }
            candidate.claim(now, now.plus(lease));
            return repository.saveAndFlush(candidate);
        });
        if (request == null) return false;
        if (request.getStatus() == Status.FAILED) {
            metrics.counter("activation.transitions", "status", Status.FAILED.name()).increment();
            return false;
        }
        try (var ignored = MDC.putCloseable("correlationId", request.getCorrelationId())) {
            log.atInfo().addKeyValue("requestId", request.getId())
                    .addKeyValue("attempt", request.getAttempts()).log("activation_started");
            Outcome outcome = provider.activate(request);
            var processed = transactions.execute(tx -> {
                var current = repository.findById(request.getId()).orElseThrow();
                if (current.getStatus() != Status.PROCESSING || current.getAttempts() != request.getAttempts()) {
                    log.atWarn().addKeyValue("requestId", request.getId())
                            .addKeyValue("attempt", request.getAttempts())
                            .addKeyValue("currentAttempt", current.getAttempts()).log("activation_stale_result_ignored");
                    return null;
                }
                var now = Instant.now();
                switch (outcome) {
                    case Success success -> current.complete(now);
                    case Rejected rejected -> current.fail(rejected.code(), now);
                    case TemporaryFailure failure -> {
                        if (current.getAttempts() >= maxAttempts) current.fail(failure.code(), now);
                        else {
                            var delay = backoff(current.getAttempts(), failure.retryAfter());
                            current.retry(failure.code(), now.plus(delay), now);
                            log.atWarn().addKeyValue("requestId", current.getId())
                                    .addKeyValue("attempt", current.getAttempts())
                                    .addKeyValue("failureCode", failure.code())
                                    .addKeyValue("retryDelayMs", delay.toMillis())
                                    .addKeyValue("nextAttemptAt", now.plus(delay)).log("activation_retry_planned");
                        }
                    }
                }
                repository.saveAndFlush(current);
                return current;
            });
            if (processed != null) {
                metrics.counter("activation.transitions", "status", processed.getStatus().name()).increment();
                log.atInfo().addKeyValue("requestId", request.getId())
                        .addKeyValue("attempt", request.getAttempts())
                        .addKeyValue("status", processed.getStatus())
                        .addKeyValue("failureCode", processed.getFailureCode())
                        .addKeyValue("outcome", outcome.getClass().getSimpleName()).log("activation_processed");
            }
        } catch (RuntimeException error) {
            log.atError().addKeyValue("requestId", request.getId())
                    .addKeyValue("correlationId", request.getCorrelationId())
                    .addKeyValue("attempt", request.getAttempts())
                    .addKeyValue("errorType", error.getClass().getName())
                    .addKeyValue("stackFrames", java.util.Arrays.toString(error.getStackTrace()))
                    .log("activation_processing_failed");
            throw error;
        }
        return true;
    }

    static Duration backoff(int attempt, Duration retryAfter) {
        long cap = Math.min(60_000, 1000L << Math.min(attempt - 1, 16));
        Duration jitter = Duration.ofMillis(ThreadLocalRandom.current().nextLong(cap / 2, cap + 1));
        return retryAfter != null && retryAfter.compareTo(jitter) > 0 ? retryAfter : jitter;
    }
}
