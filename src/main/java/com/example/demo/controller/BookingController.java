package com.example.demo.controller;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.dto.AvailableSlotsResponse;
import com.example.demo.dto.BookSlotRequest;
import com.example.demo.dto.BookingResponse;
import com.example.demo.exception.BookingException;
import com.example.demo.service.AvailableSlotsService;
import com.example.demo.service.BookingService;
import com.example.demo.service.TimeZones;

import jakarta.validation.Valid;

/** The public booking link: see open times, book one. */
@RestController
public class BookingController {

    private static final int MAX_DAYS = 62;
    private static final int MAX_HOSTS = 200;

    private final AvailableSlotsService availableSlots;
    private final BookingService bookingService;
    private final Clock clock;

    public BookingController(AvailableSlotsService availableSlots, BookingService bookingService, Clock clock) {
        this.availableSlots = availableSlots;
        this.bookingService = bookingService;
        this.clock = clock;
    }

    /**
     * GET /get-available-slots/1,2,3?tz=Europe/London&from=2026-11-02&to=2026-11-08
     * from/to are dates in the viewer's timezone; they default to today and the next 6 days.
     */
    @GetMapping("/get-available-slots/{hostIds}")
    public AvailableSlotsResponse availableSlots(
            @PathVariable String hostIds,
            @RequestParam(defaultValue = "UTC") String tz,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ZoneId viewer = TimeZones.parse(tz)
                .orElseThrow(() -> BookingException.badRequest("Unknown timezone '" + tz + "'"));
        LocalDate start = from != null ? from : LocalDate.ofInstant(clock.instant(), viewer);
        LocalDate end = to != null ? to : start.plusDays(6);
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) > MAX_DAYS) {
            throw BookingException.badRequest("'to' must be on or after 'from' and at most " + MAX_DAYS + " days later");
        }
        return availableSlots.find(parseHostIds(hostIds), viewer, start, end);
    }

    /** Books the chosen time. 201 with the booking; 409 SLOT_TAKEN / DAY_FULL with the open slots left. */
    @PostMapping("/book-slot")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse bookSlot(@Valid @RequestBody BookSlotRequest request) {
        return bookingService.book(request);
    }

    private static List<Long> parseHostIds(String csv) {
        List<Long> ids = new ArrayList<>();
        for (String part : csv.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException e) {
                throw BookingException.badRequest("Invalid host id '" + part.trim() + "'");
            }
        }
        if (ids.isEmpty() || ids.size() > MAX_HOSTS) {
            throw BookingException.badRequest("Give between 1 and " + MAX_HOSTS + " host ids");
        }
        return ids;
    }
}
