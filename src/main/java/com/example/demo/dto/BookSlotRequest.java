package com.example.demo.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Body of POST /book-slot. date/startTime/endTime are in the viewer's timezone, as returned by
 * /get-available-slots. With several hostIds (a hiring drive) the first host still free at that time
 * gets the booking. The email must belong to an invited (prefilled) user.
 * <pre>
 * {"hostIds": [1], "date": "2026-11-02", "startTime": "05:15", "endTime": "05:45",
 *  "timezone": "Europe/London", "email": "alex@example.com"}
 * </pre>
 */
public record BookSlotRequest(
        @NotEmpty List<@NotNull Long> hostIds,
        @NotNull LocalDate date,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @NotBlank String timezone,
        @NotBlank @Email String email) {
}
