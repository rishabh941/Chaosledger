package com.chaosledger.ledger.api;

import com.chaosledger.ledger.domain.*;
import com.chaosledger.ledger.domain.events.ConcurrencyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(AccountNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "ACCOUNT_NOT_FOUND",
                "message", ex.getMessage(),
                "accountId", ex.getAccountId().toString(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientBalance(InsufficientBalanceException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "error", "INSUFFICIENT_BALANCE",
                "message", ex.getMessage(),
                "accountId", ex.getAccountId().toString(),
                "currentBalance", ex.getCurrentBalance().toString(),
                "requestedAmount", ex.getRequestedAmount().toString(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(AccountClosedException.class)
    public ResponseEntity<Map<String, Object>> handleAccountClosed(AccountClosedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "error", "ACCOUNT_CLOSED",
                "message", ex.getMessage(),
                "accountId", ex.getAccountId().toString(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(DuplicateCommandException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateCommandException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "DUPLICATE_COMMAND",
                "message", ex.getMessage(),
                "idempotencyKey", ex.getIdempotencyKey().toString(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(ConcurrencyException.class)
    public ResponseEntity<Map<String, Object>> handleConcurrency(ConcurrencyException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "CONCURRENCY_CONFLICT",
                "message", ex.getMessage(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        var errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid"))
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "VALIDATION_FAILED",
                "errors", errors,
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "BAD_REQUEST",
                "message", ex.getMessage(),
                "timestamp", Instant.now().toString()
        ));
    }
}
