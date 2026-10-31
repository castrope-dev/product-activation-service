package com.product.activation.application;

import com.product.activation.application.result.*;

import com.product.activation.domain.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PostgresConcurrencyTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    RequestService service;
    @Autowired
    ActivationRepository repository;
    @Autowired
    TransactionTemplate transactions;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void concurrentCreationHasOneWinner() throws Exception {
        try (var pool = Executors.newFixedThreadPool(12)) {
            var gate = new CountDownLatch(1);
            List<Future<CreateRequestResult>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++)
                results.add(pool.submit(() -> {
                    gate.await();
                    return service.create("same", "c", "p", "owner", "trace");
                }));
            gate.countDown();
            int inserted = 0;
            Set<UUID> ids = new HashSet<>();
            for (var result : results) {
                var created = result.get(20, TimeUnit.SECONDS);
                if (created.inserted()) inserted++;
                ids.add(created.request().getId());
            }
            assertThat(inserted).isEqualTo(1);
            assertThat(ids).hasSize(1);
            assertThat(repository.count()).isEqualTo(1);
        }
    }

    private ActivationWorker worker(ActivationProvider provider, int max) {
        return new ActivationWorker(repository, transactions, provider, new SimpleMeterRegistry(), max, Duration.ofSeconds(30));
    }

    @Test
    void workersDoNotHoldDatabaseTransactionDuringHttpAndOnlyOneClaims() throws Exception {
        var created = service.create("work", "c", "p", "owner", "trace");
        var calls = new AtomicInteger();
        var worker = worker(request -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls.incrementAndGet();
            return new Success();
        }, 5);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = pool.invokeAll(List.of(worker::processOne, worker::processOne, worker::processOne, worker::processOne));
            for (var task : tasks) task.get();
        }
        assertThat(calls).hasValue(1);
        assertThat(repository.findById(created.request().getId()).orElseThrow().getStatus()).isEqualTo(Status.COMPLETED);
    }

    @Test
    void recoversExpiredLeaseAndFailsAtAttemptLimit() {
        var created = service.create("crash", "c", "p", "owner", "trace");
        transactions.executeWithoutResult(tx -> {
            var request = repository.findById(created.request().getId()).orElseThrow();
            request.claim(Instant.now(), Instant.now().minusSeconds(1));
        });
        worker(request -> new TemporaryFailure("TIMEOUT", null), 2).processOne();
        var request = repository.findById(created.request().getId()).orElseThrow();
        assertThat(request.getStatus()).isEqualTo(Status.FAILED);
        assertThat(request.getAttempts()).isEqualTo(2);
    }
}
