package com.example.demo.dto;

import java.time.Instant;

import com.example.demo.model.BookingStatus;

/** One row of a host's bookings. Times are UTC; userTimezone is where the person booked from. */
public record HostBookingResponse(
        Long bookingId,
        Long slotId,
        Instant slotStart,
        Instant slotEnd,
        BookingStatus status,
        Long userId,
        String userName,
        String userEmail,
        String userTimezone,
        boolean calendarConflict,
        Long rescheduledFromBookingId,
        Instant createdAt) {
}
