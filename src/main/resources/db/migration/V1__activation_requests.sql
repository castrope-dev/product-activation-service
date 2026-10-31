CREATE TABLE activation_request (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    external_id VARCHAR(100) NOT NULL UNIQUE,
    customer_id VARCHAR(100) NOT NULL,
    product_code VARCHAR(64) NOT NULL,
    consumer_id VARCHAR(200) NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0,
    failure_code VARCHAR(64)
);
CREATE INDEX idx_request_due ON activation_request(status, next_attempt_at, lease_until);
CREATE INDEX idx_request_consumer_created ON activation_request(consumer_id, created_at DESC, id DESC);
CREATE INDEX idx_request_consumer_status ON activation_request(consumer_id, status, created_at DESC, id DESC);
CREATE INDEX idx_request_consumer_customer ON activation_request(consumer_id, customer_id, created_at DESC, id DESC);
