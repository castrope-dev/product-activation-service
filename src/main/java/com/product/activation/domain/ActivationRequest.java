package com.product.activation.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "activation_request")
public class ActivationRequest {
    @Id
    private UUID id;
    @Version
    private long version;
    @Column(nullable = false, unique = true, length = 100)
    private String externalId;
    @Column(nullable = false, length = 100)
    private String customerId;
    @Column(nullable = false, length = 64)
    private String productCode;
    @Column(nullable = false, length = 200)
    private String consumerId;
    @Column(nullable = false, length = 64)
    private String correlationId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant nextAttemptAt;
    private Instant leaseUntil;
    private int attempts;
    @Column(length = 64)
    private String failureCode;

    protected ActivationRequest() {
    }

    public ActivationRequest(String externalId, String customerId, String productCode, String consumerId,
                             String correlationId, Instant now) {
        this.id = UUID.randomUUID();
        this.externalId = externalId;
        this.customerId = customerId;
        this.productCode = productCode;
        this.consumerId = consumerId;
        this.correlationId = correlationId;
        this.status = Status.PENDING;
        this.createdAt = this.updatedAt = this.nextAttemptAt = now;
    }

    public void claim(Instant now, Instant leaseUntil) {
        if (status != Status.PENDING && !(status == Status.PROCESSING && !this.leaseUntil.isAfter(now))) {
            throw new IllegalStateException("Request cannot be claimed");
        }
        if (nextAttemptAt.isAfter(now)) throw new IllegalStateException("Request is not due");
        status = Status.PROCESSING;
        attempts++;
        updatedAt = now;
        this.leaseUntil = leaseUntil;
    }

    public void complete(Instant now) {
        requireProcessing();
        status = Status.COMPLETED;
        finish(now);
    }

    public void fail(String code, Instant now) {
        requireProcessing();
        status = Status.FAILED;
        failureCode = code;
        finish(now);
    }

    public void retry(String code, Instant next, Instant now) {
        requireProcessing();
        failureCode = code;
        nextAttemptAt = next;
        leaseUntil = next;
        updatedAt = now;
    }

    private void finish(Instant now) {
        updatedAt = now;
        leaseUntil = null;
    }

    private void requireProcessing() {
        if (status != Status.PROCESSING) throw new IllegalStateException("Request is not processing");
    }

    public boolean sameContent(String consumer, String customer, String product) {
        return consumerId.equals(consumer) && customerId.equals(customer) && productCode.equals(product);
    }

    public UUID getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getConsumerId() {
        return consumerId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getFailureCode() {
        return failureCode;
    }
}
