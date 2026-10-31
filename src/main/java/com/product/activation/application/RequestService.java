package com.product.activation.application;

import com.product.activation.domain.*;
import com.product.activation.application.result.CreateRequestResult;
import com.product.activation.exceptions.RequestConflictException;
import com.product.activation.exceptions.RequestNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class RequestService {
    private final ActivationRepository repository;
    private final TransactionTemplate transactions;

    public CreateRequestResult create(String external, String customer, String product, String consumer, String trace) {
        var existing = repository.findByExternalId(external);
        if (existing.isPresent()) return existing(existing.get(), consumer, customer, product);
        try {
            var created = transactions.execute(tx -> new CreateRequestResult(repository.saveAndFlush(
                    new ActivationRequest(external, customer, product, consumer, trace, Instant.now())), true));
            log.atInfo().addKeyValue("requestId", created.request().getId())
                    .addKeyValue("status", created.request().getStatus()).log("request_created");
            return created;
        } catch (DataIntegrityViolationException failure) {
            var winner = repository.findByExternalId(external);
            if (winner.isEmpty()) throw failure;
            log.atDebug().log("request_concurrent_insert_recovered");
            return existing(winner.get(), consumer, customer, product);
        }
    }

    private CreateRequestResult existing(ActivationRequest request, String consumer, String customer, String product) {
        if (!request.sameContent(consumer, customer, product)) {
            log.atWarn().log("request_idempotency_conflict");
            throw new RequestConflictException();
        }
        log.atInfo().addKeyValue("requestId", request.getId())
                .addKeyValue("status", request.getStatus()).log("request_replayed");
        return new CreateRequestResult(request, false);
    }

    public ActivationRequest get(UUID id, String consumer) {
        return repository.findByIdAndConsumerId(id, consumer).orElseThrow(RequestNotFoundException::new);
    }

    public Page<ActivationRequest> list(String consumer, Status status, String customer, int page, int size) {
        Specification<ActivationRequest> filter = (root, query, cb) -> cb.equal(root.get("consumerId"), consumer);
        if (status != null) filter = filter.and((root, query, cb) -> cb.equal(root.get("status"), status));
        if (customer != null) filter = filter.and((root, query, cb) -> cb.equal(root.get("customerId"), customer));
        return repository.findAll(filter, PageRequest.of(page, size, Sort.by("createdAt", "id").descending()));
    }
}
