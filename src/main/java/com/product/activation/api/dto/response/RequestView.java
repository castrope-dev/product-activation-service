package com.product.activation.api.dto.response;

import com.product.activation.domain.ActivationRequest;
import com.product.activation.domain.Status;

import java.time.Instant;
import java.util.UUID;

public record RequestView(UUID id, String externalId, String customerId, String productCode,
                          Status status, Instant createdAt, Instant updatedAt, int attempts, String failureCode) {
    public static RequestView of(ActivationRequest request) {
        return new RequestView(request.getId(), request.getExternalId(), request.getCustomerId(),
                request.getProductCode(), request.getStatus(), request.getCreatedAt(), request.getUpdatedAt(),
                request.getAttempts(), request.getFailureCode());
    }
}
