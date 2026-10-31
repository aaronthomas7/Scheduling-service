package com.example.demo.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Response of GET /get-available-slots/{hostIds}: open times in the viewer's timezone, merged across hosts.
 * from/to are the viewer-local dates covered.
 */
public record AvailableSlotsResponse(
        String timezone,
        LocalDate from,
        LocalDate to,
        List<HostSummary> hosts,
        List<OpenTime> slots) {

    public record HostSummary(Long hostId, String name, String timezone) {
    }

    /**
     * start/end are UTC. The local fields are in the viewer's timezone and can be sent straight back
     * to /book-slot. hosts lists every host free at this time, with that host's slot id (which the
     * reschedule API takes).
     */
    public record OpenTime(
            Instant start,
            Instant end,
            String localDate,
            String localStartTime,
            String localEndTime,
            List<HostSlot> hosts) {
    }

    public record HostSlot(Long hostId, Long slotId) {
    }
}
