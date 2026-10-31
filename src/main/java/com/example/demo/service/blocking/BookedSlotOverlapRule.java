package com.example.demo.service.blocking;

import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;

import com.example.demo.model.Host;
import com.example.demo.model.Slot;
import com.example.demo.model.SlotStatus;
import com.example.demo.repository.SlotRepository;
import com.example.demo.service.SlotGenerator;

/**
 * Blocks slots that overlap an existing booking. A BOOKED slot keeps its time when the slot
 * length changes, so e.g. a new 10:00-11:00 slot can overlap a booked 10:45-11:15 one; offering
 * it would double-book the host.
 */
@Component
public class BookedSlotOverlapRule implements SlotBlockingRule {

    private final SlotRepository slots;

    public BookedSlotOverlapRule(SlotRepository slots) {
        this.slots = slots;
    }

    @Override
    public Predicate<SlotGenerator.GridSlot> forHost(Host host, Instant from, Instant to) {
        List<Slot> booked = slots.findOverlapping(host.getId(), SlotStatus.BOOKED, from, to);
        return g -> booked.stream().anyMatch(b -> g.overlaps(b.getStartUtc(), b.getEndUtc()));
    }
}
