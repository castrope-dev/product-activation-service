package com.product.activation.application.result;

import java.time.Duration;

public record TemporaryFailure(String code, Duration retryAfter) implements Outcome {
}
