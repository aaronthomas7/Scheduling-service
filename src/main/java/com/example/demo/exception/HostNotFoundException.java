package com.example.demo.exception;

public class HostNotFoundException extends RuntimeException {

    public HostNotFoundException(Long hostId) {
        super("Host " + hostId + " not found");
    }
}
