package com.example.demo.exception;

/** The schedule a host tried to publish can't be used; the message says why and is shown to the caller. */
public class InvalidAvailabilityException extends RuntimeException {

    public InvalidAvailabilityException(String message) {
        super(message);
    }
}
