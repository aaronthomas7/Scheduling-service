package com.example.demo.service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.dto.PublishAvailabilityRequest;
import com.example.demo.dto.PublishAvailabilityResponse;
import com.example.demo.exception.ApiException;
import com.example.demo.model.Host;
import com.example.demo.model.Slot;
import com.example.demo.model.SlotStatus;
import com.example.demo.repository.BookingRepository;
import com.example.demo.repository.HostRepository;
import com.example.demo.repository.SlotRepository;

@Service
public class AvailabilityService {

    private static final int PREVIEW_SLOTS = 10;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final HostRepository hosts;
    private final SlotRepository slots;
    private final BookingRepository bookings;
    private final Clock clock;
    private final int horizonDays;

    public AvailabilityService(HostRepository hosts, SlotRepository slots, BookingRepository bookings, Clock clock,
            @Value("${scheduling.horizon-days:30}") int horizonDays) {
        this.hosts = hosts;
        this.slots = slots;
        this.bookings = bookings;
        this.clock = clock;
        this.horizonDays = horizonDays;
    }

    /**
     * Saves the host's schedule and regenerates their future slots.
     *
     * Only slots that haven't started are touched, and BOOKED slots never are. A future OPEN or
     * BLOCKED slot is kept (and resized) if its start is still on the new grid, otherwise it is
     * deleted, or set to BLOCKED if a booking (e.g. a cancelled one) still references it. New grid
     * times get new OPEN slots. A slot that would overlap an existing booking, which can happen
     * after the slot length changes, is created BLOCKED so the host can't be double-booked.
     */
    @Transactional
    public PublishAvailabilityResponse publish(PublishAvailabilityRequest req) {
        ZoneId zone = parseZone(req.timezone());
        int gapMinutes = req.gapMinutes() == null ? 0 : req.gapMinutes();
        try {
            SlotGenerator.validate(req.availability(), req.slotMinutes());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }

        // Same row lock the booking transaction takes, so publishing can't reshuffle slots mid-booking.
        Host host = hosts.findByIdForUpdate(req.hostId())
                .orElseThrow(() -> ApiException.notFound("Host " + req.hostId() + " not found"));
        host.setTimezone(zone.getId());
        host.setSlotMinutes(req.slotMinutes());
        host.setGapMinutes(gapMinutes);
        host.setMaxPerDay(req.maxPerDay());
        host.setAvailability(List.copyOf(req.availability()));

        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, zone);
        LocalDate until = today.plusDays(horizonDays);
        Map<Instant, SlotGenerator.GridSlot> grid = new LinkedHashMap<>();
        for (SlotGenerator.GridSlot g : SlotGenerator.generate(host.getAvailability(), zone,
                host.getSlotMinutes(), host.getGapMinutes(), today, until)) {
            if (g.start().isAfter(now)) {
                grid.put(g.start(), g);
            }
        }

        List<Slot> unfinished = slots.findByHostIdAndEndUtcAfterOrderByStartUtc(host.getId(), now);
        List<Slot> booked = unfinished.stream().filter(s -> s.getStatus() == SlotStatus.BOOKED).toList();
        // (host_id, start_utc) is unique, so an existing row at a grid time is reused, never duplicated.
        Set<Instant> rowStarts = new HashSet<>();
        for (Slot s : unfinished) {
            rowStarts.add(s.getStartUtc());
            if (s.getStatus() == SlotStatus.BOOKED || !s.getStartUtc().isAfter(now)) {
                continue;
            }
            SlotGenerator.GridSlot g = grid.get(s.getStartUtc());
            if (g != null) {
                s.setEndUtc(g.end());
                s.setLocalDate(g.localDate());
                s.setStatus(statusFor(g, booked));
            } else if (bookings.existsBySlotId(s.getId())) {
                s.setStatus(SlotStatus.BLOCKED);
            } else {
                slots.delete(s);
            }
        }
        for (SlotGenerator.GridSlot g : grid.values()) {
            if (rowStarts.contains(g.start())) {
                continue;
            }
            Slot s = new Slot();
            s.setHost(host);
            s.setStartUtc(g.start());
            s.setEndUtc(g.end());
            s.setLocalDate(g.localDate());
            s.setStatus(statusFor(g, booked));
            slots.save(s);
        }
        slots.flush();

        List<Slot> open = slots.findByHostIdAndStatusAndStartUtcAfterOrderByStartUtc(host.getId(), SlotStatus.OPEN,
                now);
        return toResponse(host, zone, until, open);
    }

    // Once the host-calendar mirror exists, a slot covered by a calendar event should be BLOCKED here too.
    private static SlotStatus statusFor(SlotGenerator.GridSlot g, List<Slot> booked) {
        boolean overlapsBooking = booked.stream().anyMatch(b -> g.overlaps(b.getStartUtc(), b.getEndUtc()));
        return overlapsBooking ? SlotStatus.BLOCKED : SlotStatus.OPEN;
    }

    private static ZoneId parseZone(String id) {
        try {
            return ZoneId.of(id.trim());
        } catch (DateTimeException e) {
            throw ApiException.badRequest("unknown timezone '" + id + "', use an IANA name like Asia/Kolkata");
        }
    }

    private static PublishAvailabilityResponse toResponse(Host host, ZoneId zone, LocalDate until, List<Slot> open) {
        List<PublishAvailabilityResponse.SlotPreview> preview = open.stream()
                .limit(PREVIEW_SLOTS)
                .map(s -> new PublishAvailabilityResponse.SlotPreview(s.getId(), s.getStartUtc(), s.getEndUtc(),
                        s.getLocalDate(), HH_MM.format(s.getStartUtc().atZone(zone)),
                        HH_MM.format(s.getEndUtc().atZone(zone))))
                .toList();
        return new PublishAvailabilityResponse(host.getId(), host.getName(), host.getTimezone(),
                host.getSlotMinutes(), host.getGapMinutes(), host.getMaxPerDay(), host.getAvailability(), until,
                open.size(), preview);
    }
}
