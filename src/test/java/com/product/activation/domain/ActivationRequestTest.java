package com.product.activation.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class ActivationRequestTest {
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    private ActivationRequest request() {
        return new ActivationRequest("e", "c", "p", "owner", "trace", now);
    }

    @Test
    void completesOnlyAfterClaimAndRejectsTerminalTransitions() {
        var request = request();
        assertThatThrownBy(() -> request.complete(now)).isInstanceOf(IllegalStateException.class);
        request.claim(now, now.plusSeconds(30));
        request.complete(now.plusSeconds(1));
        assertThat(request.getStatus()).isEqualTo(Status.COMPLETED);
        assertThatThrownBy(() -> request.claim(now.plusSeconds(60), now.plusSeconds(90))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request.fail("error", now)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void temporaryFailureStaysProcessingAndOnlyBecomesEligibleAtScheduledTime() {
        var request = request();
        request.claim(now, now.plusSeconds(30));
        request.retry("timeout", now.plusSeconds(10), now);
        assertThat(request.getStatus()).isEqualTo(Status.PROCESSING);
        assertThatThrownBy(() -> request.claim(now.plusSeconds(9), now.plusSeconds(40))).isInstanceOf(IllegalStateException.class);
        request.claim(now.plusSeconds(10), now.plusSeconds(40));
        assertThat(request.getAttempts()).isEqualTo(2);
        request.fail("exhausted", now.plusSeconds(11));
        assertThat(request.getStatus()).isEqualTo(Status.FAILED);
    }
}
