package com.product.activation.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.product.activation.api.error.ApiProblemFactory;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;
import java.util.*;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestGuardFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    private final int bodyLimit;
    private final int rate;
    private final Map<String, RateLimitWindow> windows = new HashMap<>();

    public RequestGuardFilter(ObjectMapper mapper, @Value("${activation.api.max-body-bytes:8192}") int bodyLimit,
                              @Value("${activation.api.requests-per-minute:120}") int rate) {
        if (bodyLimit < 1 || bodyLimit == Integer.MAX_VALUE || rate < 1)
            throw new IllegalArgumentException("Invalid admission limits");
        this.mapper = mapper;
        this.bodyLimit = bodyLimit;
        this.rate = rate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader("X-Correlation-ID");
        String correlation = incoming != null && incoming.matches("[A-Za-z0-9-]{1,64}") ? incoming : UUID.randomUUID().toString();
        response.setHeader("X-Correlation-ID", correlation);
        try (var ignored = MDC.putCloseable("correlationId", correlation)) {
            if (!request.getRequestURI().startsWith("/api/")) {
                chain.doFilter(request, response);
                return;
            }
            if (!admit(request.getRemoteAddr())) {
                response.setHeader("Retry-After", "60");
                reject(429, "Limite de chamadas excedido", request, response);
                return;
            }
            if (request.getContentLengthLong() > bodyLimit) {
                reject(413, "Corpo excede o limite", request, response);
                return;
            }
            byte[] body = request.getInputStream().readNBytes(bodyLimit + 1);
            if (body.length > bodyLimit) {
                reject(413, "Corpo excede o limite", request, response);
                return;
            }
            chain.doFilter(new BufferedRequest(request, body), response);
        }
    }

    private synchronized boolean admit(String address) {
        long minute = System.currentTimeMillis() / 60_000;
        windows.entrySet().removeIf(entry -> entry.getValue().minute() != minute);
        var previous = windows.getOrDefault(address, new RateLimitWindow(minute, 0));
        if (previous.count() >= rate || (!windows.containsKey(address) && windows.size() >= 10_000)) return false;
        windows.put(address, new RateLimitWindow(minute, previous.count() + 1));
        return true;
    }

    private void reject(int status, String detail, HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        mapper.writeValue(response.getOutputStream(), ApiProblemFactory.problem(status, detail, request));
    }
}
