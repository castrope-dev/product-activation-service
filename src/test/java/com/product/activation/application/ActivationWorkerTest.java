package com.product.activation.application;

import com.product.activation.application.result.*;

import com.product.activation.domain.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class ActivationWorkerTest {
    ActivationRepository repository = mock(ActivationRepository.class);
    ActivationProvider provider = mock(ActivationProvider.class);
    ActivationRequest request;
    ActivationWorker worker;

    @BeforeEach
    void setup() {
        request = new ActivationRequest("e", "c", "p", "owner", "trace", Instant.now().minusSeconds(1));
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        worker = new ActivationWorker(repository, new TransactionTemplate(manager), provider,
                new SimpleMeterRegistry(), 2, Duration.ofSeconds(30));
        when(repository.lockNext(any())).thenReturn(Optional.of(request));
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));
    }

    @Test
    void successCompletes() {
        when(provider.activate(request)).thenReturn(new Success());
        assertThat(worker.processOne()).isTrue();
        assertThat(request.getStatus()).isEqualTo(Status.COMPLETED);
    }

    @Test
    void permanentFailureDoesNotRetry() {
        when(provider.activate(request)).thenReturn(new Rejected("PROVIDER_400"));
        worker.processOne();
        assertThat(request.getStatus()).isEqualTo(Status.FAILED);
        assertThat(request.getFailureCode()).isEqualTo("PROVIDER_400");
    }

    @Test
    void timeoutSchedulesRetryAndExhaustionFails() {
        when(provider.activate(request)).thenReturn(new TemporaryFailure("TIMEOUT", null));
        worker.processOne();
        assertThat(request.getStatus()).isEqualTo(Status.PROCESSING);
        request.retry("TIMEOUT", Instant.now().minusSeconds(1), Instant.now());
        worker.processOne();
        assertThat(request.getStatus()).isEqualTo(Status.FAILED);
        assertThat(request.getAttempts()).isEqualTo(2);
    }

    @Test
    void backoffHasJitterAndHonorsLongerProviderDelay() {
        assertThat(ActivationWorker.backoff(1, null).toMillis()).isBetween(500L, 1000L);
        assertThat(ActivationWorker.backoff(20, null).toMillis()).isBetween(30000L, 60000L);
        assertThat(ActivationWorker.backoff(1, Duration.ofSeconds(90))).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void exhaustedCrashIsNotSentAgain() {
        var now = Instant.now();
        request.claim(now, now.minusSeconds(1));
        request.claim(now, now.minusSeconds(1));
        assertThat(worker.processOne()).isFalse();
        assertThat(request.getStatus()).isEqualTo(Status.FAILED);
        verifyNoInteractions(provider);
    }

    @Test
    void ignoresResultFromAnOlderAttempt() {
        when(provider.activate(request)).thenAnswer(call -> {
            var newer = new ActivationRequest("e", "c", "p", "owner", "trace", Instant.now().minusSeconds(5));
            newer.claim(Instant.now(), Instant.now().minusSeconds(1));
            newer.claim(Instant.now(), Instant.now().plusSeconds(30));
            when(repository.findById(request.getId())).thenReturn(Optional.of(newer));
            return new Success();
        });
        worker.processOne();
        verify(repository, times(1)).saveAndFlush(any());
    }

    @Test
    void doesNotCountTransitionWhenCommitFails() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        doNothing().doThrow(new TransactionSystemException("commit failed")).when(manager).commit(any());
        var registry = new SimpleMeterRegistry();
        worker = new ActivationWorker(repository, new TransactionTemplate(manager), provider, registry, 2, Duration.ofSeconds(30));
        when(provider.activate(request)).thenReturn(new Success());
        assertThatThrownBy(worker::processOne).isInstanceOf(TransactionSystemException.class);
        assertThat(registry.find("activation.transitions").counter()).isNull();
    }
}
