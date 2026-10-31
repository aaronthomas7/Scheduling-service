package com.example.demo.service;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Optional;

/** Parses IANA zone ids; callers decide which error an unknown zone is. */
public final class TimeZones {

    private TimeZones() {
    }

    public static Optional<ZoneId> parse(String id) {
        try {
            return Optional.of(ZoneId.of(id.trim()));
        } catch (DateTimeException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
