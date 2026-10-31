package com.example.demo.controller;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.demo.model.AppUser;
import com.example.demo.model.Booking;
import com.example.demo.model.BookingStatus;
import com.example.demo.model.Host;
import com.example.demo.model.Slot;
import com.example.demo.model.SlotStatus;
import com.example.demo.repository.BookingRepository;
import com.example.demo.repository.HostRepository;
import com.example.demo.repository.SlotRepository;
import com.example.demo.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PublishAvailabilityTest.FixedClock.class)
class PublishAvailabilityTest {

    /** Sunday 10 Jan 2027, 05:30 in India. */
    static final Instant NOW = Instant.parse("2027-01-10T00:00:00Z");
    private static final AtomicLong IDS = new AtomicLong(1000);
    private static final String WEEKDAYS_10_TO_13 = """
            [{"days": [1,2,3,4,5], "start": "10:00", "end": "13:00"}]""";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    HostRepository hosts;
    @Autowired
    SlotRepository slots;
    @Autowired
    BookingRepository bookings;
    @Autowired
    UserRepository users;

    @Test
    void publishSavesTheScheduleAndGeneratesTheExampleSlots() throws Exception {
        long hostId = newHost();

        publish(body(hostId, "Asia/Kolkata", 30, 15, 4, WEEKDAYS_10_TO_13))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostId").value(hostId))
                .andExpect(jsonPath("$.timezone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.slotsGeneratedUntil").value("2027-02-09"))
                // 22 weekdays from Mon 11 Jan to Tue 9 Feb, 4 slots each.
                .andExpect(jsonPath("$.openSlots").value(88))
                .andExpect(jsonPath("$.nextOpenSlots[0].hostLocalStart").value("10:00"))
                .andExpect(jsonPath("$.nextOpenSlots[1].hostLocalStart").value("10:45"))
                .andExpect(jsonPath("$.nextOpenSlots[1].hostLocalEnd").value("11:15"))
                .andExpect(jsonPath("$.nextOpenSlots[1].start").value("2027-01-11T05:15:00Z"))
                .andExpect(jsonPath("$.nextOpenSlots[2].hostLocalStart").value("11:30"))
                .andExpect(jsonPath("$.nextOpenSlots[3].hostLocalStart").value("12:15"))
                .andExpect(jsonPath("$.nextOpenSlots[4].hostLocalDate").value("2027-01-12"));

        Host saved = hosts.findById(hostId).orElseThrow();
        assertEquals("Asia/Kolkata", saved.getTimezone());
        assertEquals(30, saved.getSlotMinutes());
        assertEquals(15, saved.getGapMinutes());
        assertEquals(4, saved.getMaxPerDay());
        assertEquals(List.of(1, 2, 3, 4, 5), saved.getAvailability().get(0).days());
        assertEquals(88, slots.findByHostIdAndEndUtcAfterOrderByStartUtc(hostId, NOW).size());
    }

    @Test
    void publishingTheSameScheduleTwiceChangesNothing() throws Exception {
        long hostId = newHost();
        String body = body(hostId, "Asia/Kolkata", 30, 15, 4, WEEKDAYS_10_TO_13);
        publish(body).andExpect(status().isOk());
        List<Long> before = slotIds(hostId);

        publish(body).andExpect(status().isOk()).andExpect(jsonPath("$.openSlots").value(88));

        assertEquals(before, slotIds(hostId));
    }

    @Test
    void republishNeverTouchesBookedSlotsAndBlocksAnythingOverlappingThem() throws Exception {
        long hostId = newHost();
        publish(body(hostId, "Asia/Kolkata", 30, 15, 4, WEEKDAYS_10_TO_13)).andExpect(status().isOk());

        Slot booked = slotAt(hostId, "2027-01-11T05:15:00Z"); // 10:45 IST
        booked.setStatus(SlotStatus.BOOKED);
        slots.save(booked);
        Slot withCancelledBooking = slotAt(hostId, "2027-01-11T06:45:00Z"); // 12:15 IST
        cancelledBookingOn(hostId, withCancelledBooking);

        // Switch to 60-minute slots with no gap: 10:00, 11:00, 12:00 IST.
        publish(body(hostId, "Asia/Kolkata", 60, 0, 4, WEEKDAYS_10_TO_13)).andExpect(status().isOk());

        assertEquals(Map.of(
                "2027-01-11T04:30:00Z", SlotStatus.BLOCKED,  // 10:00-11:00 overlaps the booked 10:45
                "2027-01-11T05:15:00Z", SlotStatus.BOOKED,   // untouched
                "2027-01-11T05:30:00Z", SlotStatus.BLOCKED,  // 11:00-12:00 overlaps the booked 10:45-11:15
                "2027-01-11T06:30:00Z", SlotStatus.OPEN,     // 12:00-13:00
                "2027-01-11T06:45:00Z", SlotStatus.BLOCKED), // off the grid, kept for the cancelled booking
                statusesOn(hostId, LocalDate.of(2027, 1, 11)));
        assertEquals(Instant.parse("2027-01-11T05:45:00Z"), slotAt(hostId, "2027-01-11T05:15:00Z").getEndUtc());
        assertEquals(Instant.parse("2027-01-11T05:30:00Z"), slotAt(hostId, "2027-01-11T04:30:00Z").getEndUtc());
    }

    @Test
    void trailingSlashWorksToo() throws Exception {
        long hostId = newHost();
        mvc.perform(post("/publish-availability/").contentType(MediaType.APPLICATION_JSON)
                .content(body(hostId, "Europe/London", 45, 15, 3, WEEKDAYS_10_TO_13)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextOpenSlots[0].start").value("2027-01-11T10:00:00Z"));
    }

    @Test
    void unknownHostIs404() throws Exception {
        publish(body(999_999, "Asia/Kolkata", 30, 15, 4, WEEKDAYS_10_TO_13))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void invalidSchedulesAreRejectedAndNothingIsSaved() throws Exception {
        long hostId = newHost();

        publish(body(hostId, "Mars/Olympus", 30, 15, 4, WEEKDAYS_10_TO_13))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("unknown timezone")));
        publish(body(hostId, "Asia/Kolkata", 30, 15, 4, """
                [{"days": [1], "start": "13:00", "end": "10:00"}]"""))
                .andExpect(status().isBadRequest());
        publish(body(hostId, "Asia/Kolkata", 30, 15, 4, """
                [{"days": [8], "start": "10:00", "end": "13:00"}]"""))
                .andExpect(status().isBadRequest());
        publish(body(hostId, "Asia/Kolkata", 30, 15, 4, """
                [{"days": [1], "start": "10:00", "end": "12:00"}, {"days": [1, 2], "start": "11:00", "end": "13:00"}]"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("overlap on Monday")));
        publish(body(hostId, "Asia/Kolkata", 2, 15, 4, WEEKDAYS_10_TO_13))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("slotMinutes")));
        publish("""
                {"hostId": %d, "timezone": "Asia/Kolkata", "slotMinutes": 30, "maxPerDay": 4}""".formatted(hostId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("availability")));
        publish("{not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));

        assertEquals("UTC", hosts.findById(hostId).orElseThrow().getTimezone());
        assertTrue(slots.findByHostIdAndEndUtcAfterOrderByStartUtc(hostId, NOW).isEmpty());
    }

    private ResultActions publish(String json) throws Exception {
        return mvc.perform(post("/publish-availability").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String body(long hostId, String timezone, int slotMinutes, int gapMinutes, int maxPerDay,
            String availability) {
        return """
                {"hostId": %d, "timezone": "%s", "slotMinutes": %d, "gapMinutes": %d, "maxPerDay": %d,
                 "availability": %s}""".formatted(hostId, timezone, slotMinutes, gapMinutes, maxPerDay, availability);
    }

    /** Hosts are prefilled rows; this stands in for that. */
    private long newHost() {
        long id = IDS.incrementAndGet();
        Host h = new Host();
        h.setId(id);
        h.setName("Host " + id);
        h.setEmail("host" + id + "@example.com");
        h.setTimezone("UTC");
        h.setAvailability(List.of());
        hosts.save(h);
        return id;
    }

    private void cancelledBookingOn(long hostId, Slot slot) {
        AppUser user = new AppUser();
        user.setId(IDS.incrementAndGet());
        user.setName("Guest");
        user.setEmail("guest" + user.getId() + "@example.com");
        users.save(user);
        Booking b = new Booking();
        b.setSlot(slot);
        b.setHost(hosts.findById(hostId).orElseThrow());
        b.setUser(user);
        b.setSlotStart(slot.getStartUtc());
        b.setSlotEnd(slot.getEndUtc());
        b.setStatus(BookingStatus.CANCELLED);
        bookings.save(b);
    }

    private Slot slotAt(long hostId, String startUtc) {
        Instant start = Instant.parse(startUtc);
        return slots.findByHostIdAndEndUtcAfterOrderByStartUtc(hostId, NOW).stream()
                .filter(s -> s.getStartUtc().equals(start))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no slot at " + startUtc));
    }

    private List<Long> slotIds(long hostId) {
        return slots.findByHostIdAndEndUtcAfterOrderByStartUtc(hostId, NOW).stream().map(Slot::getId).toList();
    }

    private Map<String, SlotStatus> statusesOn(long hostId, LocalDate hostLocalDate) {
        Map<String, SlotStatus> out = new TreeMap<>();
        for (Slot s : slots.findByHostIdAndEndUtcAfterOrderByStartUtc(hostId, NOW)) {
            if (s.getLocalDate().equals(hostLocalDate)) {
                out.put(s.getStartUtc().toString(), s.getStatus());
            }
        }
        return out;
    }
}
