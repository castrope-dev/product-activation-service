package com.product.activation.application.result;

public sealed interface Outcome permits Success, Rejected, TemporaryFailure {
}
