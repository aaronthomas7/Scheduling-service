package com.example.demo.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.example.demo.model.AvailabilityRule;

/**
 * What was saved, plus the resulting slots so the host can check them.
 * slotsGeneratedUntil is the last host-local date slots were generated for; openSlots counts
 * future OPEN slots; nextOpenSlots lists the first few of them.
 */
public record PublishAvailabilityResponse(
        Long hostId,
        String name,
        String timezone,
        int slotMinutes,
        int gapMinutes,
        int maxPerDay,
        List<AvailabilityRule> availability,
        LocalDate slotsGeneratedUntil,
        int openSlots,
        List<SlotPreview> nextOpenSlots) {

    /** start/end are UTC; the hostLocal fields are in the host's timezone. */
    public record SlotPreview(Long slotId, Instant start, Instant end, LocalDate hostLocalDate,
            String hostLocalStart, String hostLocalEnd) {
    }
}
