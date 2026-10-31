package com.product.activation.infrastructure;

import com.product.activation.application.ActivationRepository;
import com.product.activation.domain.Status;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class StatusMetrics {
    private static final Logger log = LoggerFactory.getLogger(StatusMetrics.class);
    private final ActivationRepository repository;
    private final Map<Status, AtomicLong> counts = new EnumMap<>(Status.class);

    public StatusMetrics(ActivationRepository repository, MeterRegistry metrics) {
        this.repository = repository;
        for (Status status : Status.values()) {
            var count = new AtomicLong();
            counts.put(status, count);
            metrics.gauge("activation.requests", List.of(io.micrometer.core.instrument.Tag.of("status", status.name())), count);
        }
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 10000)
    public void refresh() {
        try {
            counts.forEach((status, count) -> count.set(repository.countByStatus(status)));
        } catch (RuntimeException error) {
            log.warn("status_metrics_refresh_failed");
        }
    }
}
