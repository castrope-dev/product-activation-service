package com.product.activation.exceptions.handler;

import com.product.activation.exceptions.RequestConflictException;
import com.product.activation.exceptions.RequestNotFoundException;
import com.product.activation.exceptions.InvalidPaginationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static com.product.activation.api.error.ApiProblemFactory.problem;

import java.util.Map;
import java.util.Objects;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RequestNotFoundException.class)
    ProblemDetail missing(HttpServletRequest request) {
        return problem(404, "Solicitação não encontrada", request);
    }

    @ExceptionHandler(RequestConflictException.class)
    ProblemDetail conflict(HttpServletRequest request) {
        return problem(409, "externalId indisponível para este conteúdo", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException error, HttpServletRequest request) {
        var problem = problem(400, "Dados inválidos", request);
        problem.setProperty("errors", error.getBindingResult().getFieldErrors().stream()
                .map(field -> Map.of("field", field.getField(), "message",
                        Objects.requireNonNullElse(field.getDefaultMessage(), "Valor inválido"))).toList());
        return problem;
    }

    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, InvalidPaginationException.class})
    ProblemDetail malformed(HttpServletRequest request) {
        return problem(400, "Dados inválidos", request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception error, HttpServletRequest request) {
        if (error instanceof org.springframework.web.ErrorResponse response && response.getStatusCode().is4xxClientError()) {
            return problem(response.getStatusCode().value(), "Requisição não suportada", request);
        }
        log.atError().addKeyValue("errorType", error.getClass().getName())
                .addKeyValue("stackFrames", java.util.Arrays.toString(error.getStackTrace()))
                .log("api_failed");
        return problem(500, "Erro interno ao processar a solicitação", request);
    }

}
