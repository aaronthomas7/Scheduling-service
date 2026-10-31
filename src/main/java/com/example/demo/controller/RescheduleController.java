package com.example.demo.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.dto.BookingResponse;
import com.example.demo.dto.RescheduleRequest;
import com.example.demo.service.RescheduleService;

@RestController
@RequestMapping("/api/hosts/{hostId}/bookings")
public class RescheduleController {

    private final RescheduleService rescheduleService;

    public RescheduleController(RescheduleService rescheduleService) {
        this.rescheduleService = rescheduleService;
    }

    /** User who already booked this host picks another slot; the booking is moved atomically. */
    @PostMapping("/reschedule")
    public BookingResponse reschedule(@PathVariable Long hostId, @RequestBody RescheduleRequest request) {
        return rescheduleService.reschedule(hostId, request);
    }
}
