package com.example.demo.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.model.Host;
import com.example.demo.model.Slot;
import com.example.demo.model.SlotStatus;
import com.example.demo.repository.BookingRepository;
import com.example.demo.repository.SlotRepository;
import com.example.demo.service.blocking.SlotBlockingRule;

/**
 * Brings a host's future slot rows in line with their current settings.
 *
 * Only slots that haven't started are touched, and BOOKED slots never are. A future OPEN or
 * BLOCKED slot is kept (and resized) if its start is still on the grid, otherwise it is deleted,
 * or set to BLOCKED if a booking (e.g. a cancelled one) still references it. New grid times get
 * new rows. Each slot on the grid is OPEN unless a {@link SlotBlockingRule} blocks it.
 */
@Service
public class SlotRegenerator {

    private final SlotRepository slots;
    private final BookingRepository bookings;
    private final List<SlotBlockingRule> blockingRules;
    private final int horizonDays;

    public SlotRegenerator(SlotRepository slots, BookingRepository bookings, List<SlotBlockingRule> blockingRules,
            @Value("${scheduling.horizon-days:30}") int horizonDays) {
        this.slots = slots;
        this.bookings = bookings;
        this.blockingRules = blockingRules;
        this.horizonDays = horizonDays;
    }

    /**
     * The caller must hold the host row lock ({@code HostRepository.findByIdForUpdate}) in the
     * surrounding transaction, the same lock bookings take, so slots can't change mid-booking.
     *
     * @return the last host-local date slots were generated for
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LocalDate regenerate(Host host, Instant now) {
        ZoneId zone = ZoneId.of(host.getTimezone());
        LocalDate today = LocalDate.ofInstant(now, zone);
        LocalDate until = today.plusDays(horizonDays);

        Map<Instant, SlotGenerator.GridSlot> grid = new LinkedHashMap<>();
        for (SlotGenerator.GridSlot g : SlotGenerator.generate(host.getAvailability(), zone,
                host.getSlotMinutes(), host.getGapMinutes(), today, until)) {
            if (g.start().isAfter(now)) {
                grid.put(g.start(), g);
            }
        }
        Predicate<SlotGenerator.GridSlot> blocked = blockedTest(host, grid, now);

        // (host_id, start_utc) is unique, so an existing row at a grid time is reused, never duplicated.
        Set<Instant> rowStarts = new HashSet<>();
        for (Slot s : slots.findByHostIdAndEndUtcAfterOrderByStartUtc(host.getId(), now)) {
            rowStarts.add(s.getStartUtc());
            if (s.getStatus() == SlotStatus.BOOKED || !s.getStartUtc().isAfter(now)) {
                continue;
            }
            SlotGenerator.GridSlot g = grid.get(s.getStartUtc());
            if (g != null) {
                s.setEndUtc(g.end());
                s.setLocalDate(g.localDate());
                s.setStatus(blocked.test(g) ? SlotStatus.BLOCKED : SlotStatus.OPEN);
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
            s.setStatus(blocked.test(g) ? SlotStatus.BLOCKED : SlotStatus.OPEN);
            slots.save(s);
        }
        slots.flush();
        return until;
    }

    /** Combines every rule into one test. Rules are prepared before any slot row changes. */
    private Predicate<SlotGenerator.GridSlot> blockedTest(Host host, Map<Instant, SlotGenerator.GridSlot> grid,
            Instant now) {
        Predicate<SlotGenerator.GridSlot> none = g -> false;
        if (grid.isEmpty()) {
            return none;
        }
        Instant to = grid.values().stream().map(SlotGenerator.GridSlot::end).max(Instant::compareTo).orElseThrow();
        return blockingRules.stream()
                .map(rule -> rule.forHost(host, now, to))
                .reduce(none, Predicate::or);
    }
}
