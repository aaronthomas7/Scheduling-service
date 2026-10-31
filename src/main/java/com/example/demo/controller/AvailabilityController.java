package com.example.demo.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.dto.PublishAvailabilityRequest;
import com.example.demo.dto.PublishAvailabilityResponse;
import com.example.demo.service.AvailabilityService;

import jakarta.validation.Valid;

@RestController
public class AvailabilityController {

    private final AvailabilityService availability;

    public AvailabilityController(AvailabilityService availability) {
        this.availability = availability;
    }

    /** Sets the host's weekly availability, slot length, gap and daily cap, and regenerates their slots. */
    @PostMapping({"/publish-availability", "/publish-availability/"})
    public PublishAvailabilityResponse publish(@Valid @RequestBody PublishAvailabilityRequest request) {
        return availability.publish(request);
    }
}
