package com.example.demo.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.dto.AvailableSlotsResponse;
import com.example.demo.dto.AvailableSlotsResponse.HostSlot;
import com.example.demo.dto.AvailableSlotsResponse.OpenTime;
import com.example.demo.exception.HostNotFoundException;
import com.example.demo.model.BookingStatus;
import com.example.demo.model.Host;
import com.example.demo.model.Slot;
import com.example.demo.model.SlotStatus;
import com.example.demo.repository.BookingRepository;
import com.example.demo.repository.HostRepository;
import com.example.demo.repository.SlotRepository;

/**
 * What a person opening a booking link can pick: OPEN slots in the future whose host-local day is still
 * under the host's cap, shown in the viewer's timezone and merged across hosts (a hiring-drive link
 * lists each time once, with every host free then).
 */
@Service
public class AvailableSlotsService {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private record TimeKey(Instant start, Instant end) {
    }

    private final HostRepository hosts;
    private final SlotRepository slots;
    private final BookingRepository bookings;
    private final Clock clock;

    public AvailableSlotsService(HostRepository hosts, SlotRepository slots, BookingRepository bookings, Clock clock) {
        this.hosts = hosts;
        this.slots = slots;
        this.bookings = bookings;
        this.clock = clock;
    }

    /** Open times starting on viewer-local dates [from, to]. */
    @Transactional(readOnly = true)
    public AvailableSlotsResponse find(List<Long> hostIds, ZoneId viewer, LocalDate from, LocalDate to) {
        Instant now = clock.instant();
        Instant rangeStart = from.atStartOfDay(viewer).toInstant();
        Instant rangeEnd = to.plusDays(1).atStartOfDay(viewer).toInstant();
        if (rangeStart.isBefore(now)) {
            rangeStart = now;
        }

        List<AvailableSlotsResponse.HostSummary> found = new ArrayList<>();
        TreeMap<TimeKey, List<HostSlot>> byTime = new TreeMap<>(
                Comparator.comparing(TimeKey::start).thenComparing(TimeKey::end));
        for (Long hostId : new LinkedHashSet<>(hostIds)) {
            Host host = hosts.findById(hostId).orElse(null);
            if (host == null) {
                continue;
            }
            found.add(new AvailableSlotsResponse.HostSummary(host.getId(), host.getName(), host.getTimezone()));
            List<Slot> open = rangeStart.isBefore(rangeEnd)
                    ? slots.findStartingBetween(hostId, SlotStatus.OPEN, rangeStart, rangeEnd)
                    : List.of();
            if (open.isEmpty()) {
                continue;
            }
            Map<LocalDate, Long> bookedPerDay = bookedPerDay(hostId, open);
            for (Slot s : open) {
                if (s.getStartUtc().isAfter(now)
                        && bookedPerDay.getOrDefault(s.getLocalDate(), 0L) < host.getMaxPerDay()) {
                    byTime.computeIfAbsent(new TimeKey(s.getStartUtc(), s.getEndUtc()), k -> new ArrayList<>())
                            .add(new HostSlot(hostId, s.getId()));
                }
            }
        }
        if (found.isEmpty()) {
            throw new HostNotFoundException(hostIds.get(0));
        }

        List<OpenTime> times = byTime.entrySet().stream().map(e -> {
            ZonedDateTime start = e.getKey().start().atZone(viewer);
            ZonedDateTime end = e.getKey().end().atZone(viewer);
            return new OpenTime(e.getKey().start(), e.getKey().end(), start.toLocalDate().toString(),
                    HH_MM.format(start), HH_MM.format(end), e.getValue());
        }).toList();
        return new AvailableSlotsResponse(viewer.getId(), from, to, found, times);
    }

    private Map<LocalDate, Long> bookedPerDay(Long hostId, List<Slot> open) {
        Map<LocalDate, Long> perDay = new HashMap<>();
        LocalDate first = open.get(0).getLocalDate();
        LocalDate last = open.get(open.size() - 1).getLocalDate();
        for (Object[] row : bookings.countPerDay(hostId, BookingStatus.CONFIRMED, first, last)) {
            perDay.put((LocalDate) row[0], (Long) row[1]);
        }
        return perDay;
    }
}
