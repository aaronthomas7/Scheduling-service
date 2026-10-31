package com.example.demo.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.dto.HostBookingResponse;
import com.example.demo.service.HostBookingsService;

/** Read side for the host page; sits next to RescheduleController under the same path. */
@RestController
@RequestMapping("/api/hosts/{hostId}/bookings")
public class HostBookingsController {

    private final HostBookingsService hostBookings;

    public HostBookingsController(HostBookingsService hostBookings) {
        this.hostBookings = hostBookings;
    }

    /** Every booking for the host, earliest first, in any status (CONFIRMED, RESCHEDULED, CANCELLED). */
    @GetMapping
    public List<HostBookingResponse> list(@PathVariable Long hostId) {
        return hostBookings.list(hostId);
    }
}
