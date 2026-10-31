package com.product.activation.application;

import com.product.activation.domain.ActivationRequest;
import org.springframework.data.jpa.repository.*;

import java.time.Instant;
import java.util.*;

public interface ActivationRepository extends JpaRepository<ActivationRequest, UUID>, JpaSpecificationExecutor<ActivationRequest> {
    Optional<ActivationRequest> findByExternalId(String externalId);

    Optional<ActivationRequest> findByIdAndConsumerId(UUID id, String consumerId);

    long countByStatus(com.product.activation.domain.Status status);

    @Query(value = """
            SELECT * FROM activation_request
            WHERE (status = 'PENDING' AND next_attempt_at <= :now)
               OR (status = 'PROCESSING' AND lease_until <= :now AND next_attempt_at <= :now)
            ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<ActivationRequest> lockNext(Instant now);
}
