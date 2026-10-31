package com.example.demo.controller;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.demo.exception.HostNotFoundException;
import com.example.demo.exception.InvalidAvailabilityException;

/** Maps domain exceptions to HTTP; services never deal with status codes. */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ErrorBody(String error, String message) {
    }

    @ExceptionHandler(HostNotFoundException.class)
    public ResponseEntity<ErrorBody> handleHostNotFound(HostNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(InvalidAvailabilityException.class)
    public ResponseEntity<ErrorBody> handleInvalidAvailability(InvalidAvailabilityException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_AVAILABILITY", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleInvalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleUnreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Body is not valid JSON or a field has the wrong type");
    }

    private static ResponseEntity<ErrorBody> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorBody(code, message));
    }
}
