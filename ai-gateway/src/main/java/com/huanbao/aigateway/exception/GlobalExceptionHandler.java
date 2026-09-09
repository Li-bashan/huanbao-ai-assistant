package com.huanbao.aigateway.exception;

import com.huanbao.aigateway.common.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<byte[]> handleBusiness(BusinessException ex) {
        return json(ApiResponse.fail(ex.getCode(), ex.getMessage()), statusFor(ex.getCode()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<byte[]> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(this::formatFieldError)
            .collect(Collectors.joining("; "));
        return json(ApiResponse.fail("VALIDATION_ERROR", message), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<byte[]> handleConstraintViolation(ConstraintViolationException ex) {
        return json(ApiResponse.fail("VALIDATION_ERROR", ex.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<byte[]> handleDataAccess(DataAccessException ex) {
        log.error("Database operation failed", ex);
        return json(ApiResponse.fail("DB_ERROR", "database operation failed"), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<byte[]> handleUnknown(Exception ex) {
        log.error("Unhandled server error", ex);
        return json(ApiResponse.fail("SERVER_ERROR", "internal server error"), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<byte[]> json(ApiResponse<Void> body, HttpStatus status) {
        byte[] content;
        try {
            content = OBJECT_MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException ex) {
            content = body.toString().getBytes(StandardCharsets.UTF_8);
        }
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(content);
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }

    private HttpStatus statusFor(String code) {
        if ("UNAUTHENTICATED".equals(code)
            || "IDENTITY_UNTRUSTED".equals(code)
            || "IDENTITY_UNVERIFIED".equals(code)
            || "IDENTITY_CONFIG_MISSING".equals(code)
            || "CURRENT_USER_MISSING".equals(code)) {
            return HttpStatus.UNAUTHORIZED;
        }
        if ("DATA_QUERY_NOT_COVERED".equals(code)
            || "ACCESS_DENIED".equals(code)
            || "DATA_SCOPE_DENIED".equals(code)
            || "CONVERSATION_OWNERSHIP_MISMATCH".equals(code)) {
            return HttpStatus.FORBIDDEN;
        }
        if ("RATE_LIMITED".equals(code)) return HttpStatus.TOO_MANY_REQUESTS;
        if (code != null && code.startsWith("DIFY_TIMEOUT")) return HttpStatus.GATEWAY_TIMEOUT;
        if (code != null && (code.startsWith("DIFY_") || "ANALYSIS_FAILED".equals(code))) {
            return HttpStatus.BAD_GATEWAY;
        }
        return HttpStatus.BAD_REQUEST;
    }
}
