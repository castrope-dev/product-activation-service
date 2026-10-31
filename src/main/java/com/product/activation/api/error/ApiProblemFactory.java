package com.product.activation.api.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

public final class ApiProblemFactory {
    private ApiProblemFactory() {
    }

    public static ProblemDetail problem(int status, String detail, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("traceId", MDC.get("correlationId"));
        return problem;
    }
}
