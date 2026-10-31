package com.example.demo.dto;

import java.time.Instant;

import com.example.demo.model.BookingStatus;

/** Times are UTC instants; clients convert to the viewer's timezone. */
public record BookingResponse(
        Long bookingId,
        Long hostId,
        Long userId,
        Long slotId,
        Instant slotStart,
        Instant slotEnd,
        BookingStatus status,
        String userTimezone,
        Long rescheduledFromBookingId) {
}
