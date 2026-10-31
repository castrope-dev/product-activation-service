package com.product.activation.api;

import com.product.activation.application.RequestService;
import com.product.activation.api.dto.request.CreateRequest;
import com.product.activation.api.dto.response.RequestView;
import com.product.activation.api.dto.response.PageView;
import com.product.activation.exceptions.InvalidPaginationException;
import com.product.activation.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

import java.util.*;

@Validated
@RestController
@RequestMapping("/api/v1/requests")
@RequiredArgsConstructor
public class RequestController {
    private final RequestService service;

    @PostMapping
    public ResponseEntity<RequestView> create(@Valid @RequestBody CreateRequest body, @AuthenticationPrincipal Jwt jwt) {
        var result = service.create(body.externalId(), body.customerId(), body.productCode(), jwt.getSubject(), MDC.get("correlationId"));
        return ResponseEntity.status(result.inserted() ? 201 : 200)
                .location(URI.create("/api/v1/requests/" + result.request().getId())).body(RequestView.of(result.request()));
    }

    @GetMapping("/{id}")
    public RequestView get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return RequestView.of(service.get(id, jwt.getSubject()));
    }

    @GetMapping
    public PageView list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Status status,
                         @RequestParam(required = false) @Size(max = 100) String customerId,
                         @RequestParam(defaultValue = "0") @Min(0) int page,
                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        if ((long) page * size > Integer.MAX_VALUE) throw new InvalidPaginationException();
        var result = service.list(jwt.getSubject(), status, customerId, page, size);
        return new PageView(result.getContent().stream().map(RequestView::of).toList(), page, size,
                result.getTotalElements(), result.getTotalPages());
    }
}
