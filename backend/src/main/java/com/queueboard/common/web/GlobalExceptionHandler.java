package com.queueboard.common.web;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.queueboard.job.domain.exception.InvalidJobTransitionException;
import com.queueboard.job.domain.exception.JobNotFoundException;
import com.queueboard.job.domain.exception.RetryNotAllowedException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Single place where domain exceptions become HTTP responses, as RFC 9457
 * Problem Details ({@code application/problem+json}) — consistent error shape
 * across every endpoint (contract: docs/api/openapi.yaml #/components/schemas/ProblemDetail).
 * Returning {@link ProblemDetail} lets the framework set the status code and the
 * problem+json content type.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Invalid input at the edge → 400 with field-level errors. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onValidationFailure(MethodArgumentNotValidException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request", "Validation failed");
        problem.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> Map.of(
                        "field", fieldError.getField(),
                        "message", String.valueOf(fieldError.getDefaultMessage())))
                .toList());
        return problem;
    }

    /**
     * A path/query value Spring could not convert. A malformed job id denotes a job that
     * cannot exist → 404, same as an unknown id (the contract declares no 400 for
     * {@code GET /jobs/{id}}). Anything else (e.g. an unknown {@code ?status=}) → 400.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail onTypeMismatch(MethodArgumentTypeMismatchException ex) {
        if (UUID.class.equals(ex.getRequiredType())) {
            return problem(HttpStatus.NOT_FOUND, "Job not found", "No job with id " + ex.getValue());
        }
        return problem(HttpStatus.BAD_REQUEST, "Invalid parameter",
                "Invalid value '%s' for parameter '%s'".formatted(ex.getValue(), ex.getName()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail onUnreadableBody(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed request body",
                "The request body could not be read as JSON");
    }

    @ExceptionHandler(JobNotFoundException.class)
    ProblemDetail onJobNotFound(JobNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Job not found", ex.getMessage());
    }

    @ExceptionHandler({InvalidJobTransitionException.class, RetryNotAllowedException.class})
    ProblemDetail onLifecycleConflict(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail onOptimisticLockingConflict(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict",
                "The job was modified concurrently; reload and try again");
    }

    /** Fallback: logged with full detail, returned without internals. */
    @ExceptionHandler(Exception.class)
    ProblemDetail onUnexpected(Exception ex, HttpServletRequest request) {
        // Framework exceptions (unknown URL → 404, wrong method → 405, …) carry their own
        // status; let them keep it instead of collapsing everything into a 500.
        if (ex instanceof ErrorResponse errorResponse) {
            return errorResponse.getBody();
        }
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected error occurred");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
