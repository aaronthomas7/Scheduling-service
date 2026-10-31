package com.example.demo.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.example.demo.model.AvailabilityRule;
import com.example.demo.model.Host;
import com.example.demo.model.Slot;

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

    private static final int PREVIEW_SLOTS = 10;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** start/end are UTC; the hostLocal fields are in the host's timezone. */
    public record SlotPreview(Long slotId, Instant start, Instant end, LocalDate hostLocalDate,
            String hostLocalStart, String hostLocalEnd) {
    }

    public static PublishAvailabilityResponse from(Host host, LocalDate slotsGeneratedUntil, List<Slot> openSlots) {
        ZoneId zone = ZoneId.of(host.getTimezone());
        List<SlotPreview> preview = openSlots.stream()
                .limit(PREVIEW_SLOTS)
                .map(s -> new SlotPreview(s.getId(), s.getStartUtc(), s.getEndUtc(), s.getLocalDate(),
                        HH_MM.format(s.getStartUtc().atZone(zone)), HH_MM.format(s.getEndUtc().atZone(zone))))
                .toList();
        return new PublishAvailabilityResponse(host.getId(), host.getName(), host.getTimezone(),
                host.getSlotMinutes(), host.getGapMinutes(), host.getMaxPerDay(), host.getAvailability(),
                slotsGeneratedUntil, openSlots.size(), preview);
    }
}
