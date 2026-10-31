package com.example.demo.service.blocking;

import java.time.Instant;
import java.util.function.Predicate;

import com.example.demo.model.Host;
import com.example.demo.service.SlotGenerator;

/**
 * Decides whether a slot that is on the host's availability grid must be held back (BLOCKED)
 * instead of offered (OPEN). Every Spring bean implementing this is applied when slots are
 * regenerated, so a new reason to block a slot, e.g. the host's own calendar being busy, is a new
 * bean rather than an edit to the regenerator.
 */
@FunctionalInterface
public interface SlotBlockingRule {

    /**
     * Called once per regeneration so the rule can load what it needs in one query instead of one
     * per slot. The returned test is true for slots that must not be offered.
     *
     * @param from start of the window being regenerated (now)
     * @param to   end of the last slot being regenerated
     */
    Predicate<SlotGenerator.GridSlot> forHost(Host host, Instant from, Instant to);
}
