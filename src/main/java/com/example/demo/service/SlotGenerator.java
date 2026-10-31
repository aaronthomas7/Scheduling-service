package com.example.demo.service;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.example.demo.exception.InvalidAvailabilityException;
import com.example.demo.model.AvailabilityRule;

/**
 * Turns weekly availability into concrete slot times. Pure: no database, no clock.
 *
 * Slots are laid out on the host's wall clock, so 10:00 stays 10:00 for the host across DST
 * changes. Within a window: start, then start + slot + gap, ... while the slot still ends inside
 * the window. 10:00-13:00 with 30-minute slots and a 15-minute gap gives 10:00, 10:45, 11:30, 12:15.
 */
public final class SlotGenerator {

    /** One slot on the grid. localDate is the host's calendar day (used for the daily cap). */
    public record GridSlot(Instant start, Instant end, LocalDate localDate) {

        public boolean overlaps(Instant otherStart, Instant otherEnd) {
            return start.isBefore(otherEnd) && otherStart.isBefore(end);
        }
    }

    private SlotGenerator() {
    }

    /**
     * Rejects availability that would produce no slots or overlapping slots.
     *
     * @throws InvalidAvailabilityException with a message meant for the caller
     */
    public static void validate(List<AvailabilityRule> rules, int slotMinutes) {
        if (rules == null || rules.isEmpty()) {
            throw new InvalidAvailabilityException("availability needs at least one rule");
        }
        Map<Integer, List<LocalTime[]>> windowsByDay = new TreeMap<>();
        for (AvailabilityRule rule : rules) {
            if (rule == null || rule.days() == null || rule.days().isEmpty()) {
                throw new InvalidAvailabilityException("every availability rule needs days, e.g. [1,2,3,4,5]");
            }
            LocalTime start = parseTime(rule.start());
            LocalTime end = parseTime(rule.end());
            if (!end.isAfter(start)) {
                throw new InvalidAvailabilityException(
                        "window " + rule.start() + "-" + rule.end() + " must end after it starts (same day)");
            }
            if (Duration.between(start, end).toMinutes() < slotMinutes) {
                throw new InvalidAvailabilityException("window " + rule.start() + "-" + rule.end()
                        + " is shorter than one " + slotMinutes + "-minute slot");
            }
            for (Integer day : rule.days()) {
                if (day == null || day < 1 || day > 7) {
                    throw new InvalidAvailabilityException("days must be ISO weekdays 1 (Monday) to 7 (Sunday), got " + day);
                }
                windowsByDay.computeIfAbsent(day, d -> new ArrayList<>()).add(new LocalTime[] {start, end});
            }
        }
        // Overlapping windows on one day would produce overlapping slots, i.e. the host double-booked.
        windowsByDay.forEach((day, windows) -> {
            windows.sort(Comparator.comparing(w -> w[0]));
            for (int i = 1; i < windows.size(); i++) {
                LocalTime[] prev = windows.get(i - 1);
                LocalTime[] cur = windows.get(i);
                if (cur[0].isBefore(prev[1])) {
                    throw new InvalidAvailabilityException("availability windows overlap on "
                            + DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ": "
                            + prev[0] + "-" + prev[1] + " and " + cur[0] + "-" + cur[1]);
                }
            }
        });
    }

    /** Slots for host-local dates [from, to], sorted by start. Assumes {@link #validate} passed. */
    public static List<GridSlot> generate(List<AvailabilityRule> rules, ZoneId zone, int slotMinutes,
            int gapMinutes, LocalDate from, LocalDate to) {
        TreeMap<Instant, GridSlot> byStart = new TreeMap<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            int isoDay = date.getDayOfWeek().getValue();
            for (AvailabilityRule rule : rules) {
                if (rule.days().contains(isoDay)) {
                    addWindow(byStart, date, parseTime(rule.start()), parseTime(rule.end()), zone, slotMinutes,
                            gapMinutes);
                }
            }
        }
        // Validated windows never overlap, but a DST jump can shift a slot onto its neighbour
        // (e.g. 02:00 becoming 03:00 on spring-forward). Keep the earlier one so slots never overlap.
        List<GridSlot> slots = new ArrayList<>();
        for (GridSlot s : byStart.values()) {
            if (slots.isEmpty() || !s.start().isBefore(slots.get(slots.size() - 1).end())) {
                slots.add(s);
            }
        }
        return slots;
    }

    private static void addWindow(TreeMap<Instant, GridSlot> out, LocalDate date, LocalTime windowStart,
            LocalTime windowEnd, ZoneId zone, int slotMinutes, int gapMinutes) {
        LocalTime t = windowStart;
        while (true) {
            LocalTime slotEnd = t.plusMinutes(slotMinutes);
            // LocalTime wraps at midnight; a wrapped end is never inside the window.
            if (!slotEnd.isAfter(t) || slotEnd.isAfter(windowEnd)) {
                return;
            }
            Instant start = ZonedDateTime.of(date, t, zone).toInstant();
            Instant end = ZonedDateTime.of(date, slotEnd, zone).toInstant();
            if (end.isAfter(start)) {
                out.putIfAbsent(start, new GridSlot(start, end, date));
            }
            LocalTime next = slotEnd.plusMinutes(gapMinutes);
            if (next.isBefore(slotEnd)) {
                return;
            }
            t = next;
        }
    }

    private static LocalTime parseTime(String hhmm) {
        try {
            return LocalTime.parse(hhmm);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new InvalidAvailabilityException("invalid time '" + hhmm + "', expected HH:mm like 10:00");
        }
    }

    /** Parses an IANA zone id like Asia/Kolkata. */
    public static ZoneId parseZone(String id) {
        try {
            return ZoneId.of(id.trim());
        } catch (DateTimeException | NullPointerException e) {
            throw new InvalidAvailabilityException("unknown timezone '" + id + "', use an IANA name like Asia/Kolkata");
        }
    }
}
