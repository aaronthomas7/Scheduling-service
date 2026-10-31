package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.demo.exception.InvalidAvailabilityException;
import com.example.demo.model.AvailabilityRule;

class SlotGeneratorTest {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final List<AvailabilityRule> WEEKDAYS_10_TO_13 =
            List.of(new AvailabilityRule(List.of(1, 2, 3, 4, 5), "10:00", "13:00"));
    private static final LocalDate MONDAY = LocalDate.of(2027, 1, 11);

    @Test
    void problemStatementExample() {
        List<SlotGenerator.GridSlot> slots = SlotGenerator.generate(WEEKDAYS_10_TO_13, INDIA, 30, 15, MONDAY, MONDAY);

        assertEquals(List.of("10:00", "10:45", "11:30", "12:15"),
                slots.stream().map(s -> s.start().atZone(INDIA).toLocalTime().toString()).toList());
        assertTrue(slots.stream().allMatch(s -> Duration.between(s.start(), s.end()).toMinutes() == 30));
        assertTrue(slots.stream().allMatch(s -> s.localDate().equals(MONDAY)));
        // A viewer in London in January sees the 10:45 IST slot as 5:15 AM.
        assertEquals(Instant.parse("2027-01-11T05:15:00Z"), slots.get(1).start());
        assertEquals(LocalTime.of(5, 15), slots.get(1).start().atZone(ZoneId.of("Europe/London")).toLocalTime());
    }

    @Test
    void noSlotsOnDaysWithoutAvailability() {
        LocalDate saturday = LocalDate.of(2027, 1, 16);
        assertTrue(SlotGenerator.generate(WEEKDAYS_10_TO_13, INDIA, 30, 15, saturday, saturday).isEmpty());
    }

    @Test
    void slotsKeepTheHostsWallClockAcrossDst() {
        ZoneId newYork = ZoneId.of("America/New_York");
        List<AvailabilityRule> rules = List.of(new AvailabilityRule(List.of(1, 2, 3, 4, 5), "09:00", "10:00"));
        // US clocks move forward on Sunday 2027-03-14.
        List<SlotGenerator.GridSlot> slots = SlotGenerator.generate(rules, newYork, 30, 0,
                LocalDate.of(2027, 3, 12), LocalDate.of(2027, 3, 15));

        assertEquals(List.of(
                Instant.parse("2027-03-12T14:00:00Z"), Instant.parse("2027-03-12T14:30:00Z"),
                Instant.parse("2027-03-15T13:00:00Z"), Instant.parse("2027-03-15T13:30:00Z")),
                slots.stream().map(SlotGenerator.GridSlot::start).toList());
    }

    @Test
    void slotsNeverOverlapEvenAcrossTheDstGap() {
        ZoneId newYork = ZoneId.of("America/New_York");
        List<AvailabilityRule> rules = List.of(new AvailabilityRule(List.of(7), "01:00", "04:00"));
        LocalDate springForward = LocalDate.of(2027, 3, 14);
        List<SlotGenerator.GridSlot> slots = SlotGenerator.generate(rules, newYork, 30, 0, springForward, springForward);

        for (int i = 1; i < slots.size(); i++) {
            assertTrue(!slots.get(i).start().isBefore(slots.get(i - 1).end()), "overlap at " + slots.get(i));
        }
        assertTrue(slots.stream().allMatch(s -> s.end().isAfter(s.start())));
    }

    @Test
    void rejectsOverlappingWindowsOnTheSameDay() {
        List<AvailabilityRule> rules = List.of(
                new AvailabilityRule(List.of(1), "10:00", "12:00"),
                new AvailabilityRule(List.of(1, 2), "11:00", "13:00"));
        InvalidAvailabilityException e = assertThrows(InvalidAvailabilityException.class,
                () -> SlotGenerator.validate(rules, 30));
        assertTrue(e.getMessage().contains("overlap on Monday"), e.getMessage());
    }

    @Test
    void allowsBackToBackWindows() {
        SlotGenerator.validate(List.of(
                new AvailabilityRule(List.of(1), "09:00", "10:00"),
                new AvailabilityRule(List.of(1), "10:00", "11:00")), 30);
    }

    @Test
    void rejectsWindowsThatCannotHoldOneSlot() {
        assertThrows(InvalidAvailabilityException.class, () -> SlotGenerator.validate(
                List.of(new AvailabilityRule(List.of(1), "10:00", "10:20")), 30));
        assertThrows(InvalidAvailabilityException.class, () -> SlotGenerator.validate(
                List.of(new AvailabilityRule(List.of(1), "13:00", "10:00")), 30));
    }

    @Test
    void rejectsBadDaysAndTimes() {
        assertThrows(InvalidAvailabilityException.class, () -> SlotGenerator.validate(
                List.of(new AvailabilityRule(List.of(8), "10:00", "13:00")), 30));
        assertThrows(InvalidAvailabilityException.class, () -> SlotGenerator.validate(
                List.of(new AvailabilityRule(List.of(), "10:00", "13:00")), 30));
        assertThrows(InvalidAvailabilityException.class, () -> SlotGenerator.validate(
                List.of(new AvailabilityRule(List.of(1), "10am", "13:00")), 30));
        assertThrows(InvalidAvailabilityException.class, () -> SlotGenerator.validate(List.of(), 30));
    }

    @Test
    void parsesIanaZonesAndRejectsOthers() {
        assertEquals(INDIA, SlotGenerator.parseZone(" Asia/Kolkata "));
        assertThrows(InvalidAvailabilityException.class, () -> SlotGenerator.parseZone("Mars/Olympus"));
    }
}
