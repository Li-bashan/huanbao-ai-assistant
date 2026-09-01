package com.huanbao.aigateway.exception;

import com.huanbao.aigateway.common.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(statusFor(ex.getCode()))
            .body(ApiResponse.fail(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(this::formatFieldError)
            .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiResponse.fail("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.fail("VALIDATION_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccess(DataAccessException ex) {
        log.error("Database operation failed", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.fail("DB_ERROR", "database operation failed"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception ex) {
        log.error("Unhandled server error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.fail("SERVER_ERROR", "internal server error"));
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
