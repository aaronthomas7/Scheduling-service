package com.example.demo.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.dto.PublishAvailabilityRequest;
import com.example.demo.dto.PublishAvailabilityResponse;
import com.example.demo.exception.HostNotFoundException;
import com.example.demo.model.Host;
import com.example.demo.model.Slot;
import com.example.demo.model.SlotStatus;
import com.example.demo.repository.HostRepository;
import com.example.demo.repository.SlotRepository;

@Service
public class AvailabilityService {

    private final HostRepository hosts;
    private final SlotRepository slots;
    private final SlotRegenerator slotRegenerator;
    private final Clock clock;

    public AvailabilityService(HostRepository hosts, SlotRepository slots, SlotRegenerator slotRegenerator,
            Clock clock) {
        this.hosts = hosts;
        this.slots = slots;
        this.slotRegenerator = slotRegenerator;
        this.clock = clock;
    }

    /** Saves the host's schedule and regenerates their future slots, in one transaction. */
    @Transactional
    public PublishAvailabilityResponse publish(PublishAvailabilityRequest req) {
        ZoneId zone = SlotGenerator.parseZone(req.timezone());
        SlotGenerator.validate(req.availability(), req.slotMinutes());

        // Same row lock the booking transaction takes, so publishing can't reshuffle slots mid-booking.
        Host host = hosts.findByIdForUpdate(req.hostId()).orElseThrow(() -> new HostNotFoundException(req.hostId()));
        host.setTimezone(zone.getId());
        host.setSlotMinutes(req.slotMinutes());
        host.setGapMinutes(req.gapMinutes() == null ? 0 : req.gapMinutes());
        host.setMaxPerDay(req.maxPerDay());
        host.setAvailability(List.copyOf(req.availability()));

        Instant now = clock.instant();
        LocalDate until = slotRegenerator.regenerate(host, now);
        List<Slot> open = slots.findByHostIdAndStatusAndStartUtcAfterOrderByStartUtc(host.getId(), SlotStatus.OPEN,
                now);
        return PublishAvailabilityResponse.from(host, until, open);
    }
}
