package com.expensetracker.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApi(ApiException ex, HttpServletRequest req) {
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getDetails(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage()).toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", details, req);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraint(ConstraintViolationException ex, HttpServletRequest req) {
        List<String> details = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage()).toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", details, req);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiErrorResponse> handleBadInput(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Malformed request: " + rootMessage(ex), List.of(), req);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleUpload(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Uploaded file is too large", List.of(), req);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleDenied(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied", List.of(), req);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleLock(OptimisticLockingFailureException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "This record was changed by another request. Reload and try again.", List.of(), req);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("Data integrity violation on {}: {}", req.getRequestURI(), rootMessage(ex));
        return build(HttpStatus.CONFLICT, "CONFLICT", "The change conflicts with existing data (duplicate name or a record still in use).", List.of(), req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest req) {
        // Spring's own client errors (unknown URL 404, wrong method 405, missing parameter 400, wrong content type 415)
        // carry their status: return it, and don't fill the logs with stack traces when bots probe a public server.
        if (ex instanceof org.springframework.web.ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            HttpStatus status = HttpStatus.valueOf(er.getStatusCode().value());
            log.debug("{} on {} {}: {}", status.value(), req.getMethod(), req.getRequestURI(), ex.getMessage());
            String message = status == HttpStatus.NOT_FOUND ? "Not found" : status == HttpStatus.METHOD_NOT_ALLOWED ? "Method not allowed"
                    : status == HttpStatus.UNSUPPORTED_MEDIA_TYPE ? "Unsupported content type" : "Bad request: " + rootMessage(ex);
            return build(status, status.name(), message, List.of(), req);
        }
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong. The error has been logged.", List.of(), req);
    }

    private ResponseEntity<ApiErrorResponse> build(HttpStatus status, String code, String message, List<String> details, HttpServletRequest req) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(new ApiErrorResponse.Error(code, message, details), Instant.now(), req.getRequestURI()));
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : (m.length() > 200 ? m.substring(0, 200) : m);
    }
}
