package com.example.demo.controller;

import java.time.Clock;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.demo.dto.ErrorResponse;
import com.example.demo.dto.SlotResponse;
import com.example.demo.exception.BookingException;
import com.example.demo.exception.HostNotFoundException;
import com.example.demo.exception.InvalidAvailabilityException;
import com.example.demo.model.SlotStatus;
import com.example.demo.repository.SlotRepository;

/** Maps domain exceptions to HTTP; services never deal with status codes. */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ErrorBody(String error, String message) {
    }

    private final SlotRepository slots;
    private final Clock clock;

    public ApiExceptionHandler(SlotRepository slots, Clock clock) {
        this.slots = slots;
        this.clock = clock;
    }

    /** Booking and reschedule refusals. "Slot taken" and "day full" also return what is still open so the user can re-pick. */
    @ExceptionHandler(BookingException.class)
    public ResponseEntity<ErrorResponse> handleBooking(BookingException e) {
        HttpStatus status = switch (e.getReason()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case TOO_LATE_TO_CHANGE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case SLOT_TAKEN, DAY_FULL, ALREADY_BOOKED -> HttpStatus.CONFLICT;
        };
        List<SlotResponse> open = e.getHostId() == null ? null : openSlots(e.getHostId());
        return ResponseEntity.status(status).body(new ErrorResponse(e.getCode(), e.getMessage(), open));
    }

    /** A unique index (one active booking per slot) rejected a concurrent insert: same as "just taken". */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorBody> handleConflict(DataIntegrityViolationException e) {
        return error(HttpStatus.CONFLICT, "SLOT_TAKEN", "This time was just taken");
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

    private List<SlotResponse> openSlots(Long hostId) {
        return slots.findByHostIdAndStatusAndStartUtcAfterOrderByStartUtc(hostId, SlotStatus.OPEN, clock.instant())
                .stream()
                .map(s -> new SlotResponse(s.getId(), s.getStartUtc(), s.getEndUtc()))
                .toList();
    }
}
