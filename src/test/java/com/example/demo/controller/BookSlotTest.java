package com.example.demo.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

import com.example.demo.dto.BookSlotRequest;
import com.example.demo.dto.PublishAvailabilityRequest;
import com.example.demo.exception.BookingException;
import com.example.demo.model.AppUser;
import com.example.demo.model.AvailabilityRule;
import com.example.demo.model.BookingStatus;
import com.example.demo.model.Host;
import com.example.demo.model.SlotStatus;
import com.example.demo.repository.BookingRepository;
import com.example.demo.repository.HostRepository;
import com.example.demo.repository.SlotRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.AvailabilityService;
import com.example.demo.service.BookingService;

@SpringBootTest
@AutoConfigureMockMvc
@Import(BookSlotTest.FixedClock.class)
class BookSlotTest {

    /** Sunday 10 Jan 2027. Monday 11 Jan is the first bookable day for the weekday hosts below. */
    static final Instant NOW = Instant.parse("2027-01-10T00:00:00Z");
    static final String MONDAY = "2027-01-11";
    private static final AtomicLong IDS = new AtomicLong(5000);

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
    UserRepository users;
    @Autowired
    SlotRepository slots;
    @Autowired
    BookingRepository bookings;
    @Autowired
    AvailabilityService availability;
    @Autowired
    BookingService bookingService;

    @Test
    void openSlotsAreShownInTheViewersTimezone() throws Exception {
        long hostId = indiaHost(4);

        // 10:00, 10:45, 11:30, 12:15 IST seen from London in January.
        availableSlots(String.valueOf(hostId), "Europe/London", MONDAY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Europe/London"))
                .andExpect(jsonPath("$.hosts[0].hostId").value(hostId))
                .andExpect(jsonPath("$.slots[*].localStartTime").value(contains("04:30", "05:15", "06:00", "06:45")))
                .andExpect(jsonPath("$.slots[1].localEndTime").value("05:45"))
                .andExpect(jsonPath("$.slots[1].start").value("2027-01-11T05:15:00Z"))
                .andExpect(jsonPath("$.slots[1].hosts[0].hostId").value(hostId));
    }

    @Test
    void bookingMarksTheSlotBookedAndTheHostSeesIt() throws Exception {
        long hostId = indiaHost(4);
        String alex = invitedUser();

        book(List.of(hostId), "05:15", "05:45", alex)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.hostId").value(hostId))
                .andExpect(jsonPath("$.slotStart").value("2027-01-11T05:15:00Z"))
                .andExpect(jsonPath("$.userTimezone").value("Europe/London"));

        assertEquals(SlotStatus.BOOKED, slotAt(hostId, "2027-01-11T05:15:00Z").getStatus());
        availableSlots(String.valueOf(hostId), "Europe/London", MONDAY)
                .andExpect(jsonPath("$.slots[*].localStartTime").value(contains("04:30", "06:00", "06:45")));
        mvc.perform(get("/api/hosts/{hostId}/bookings", hostId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$[0].userEmail").value(alex))
                .andExpect(jsonPath("$[0].slotStart").value("2027-01-11T05:15:00Z"))
                .andExpect(jsonPath("$[0].userTimezone").value("Europe/London"));
    }

    @Test
    void secondPersonForTheSameTimeGets409WithTheOpenSlots() throws Exception {
        long hostId = indiaHost(4);
        book(List.of(hostId), "05:15", "05:45", invitedUser()).andExpect(status().isCreated());
        long bookedSlotId = slotAt(hostId, "2027-01-11T05:15:00Z").getId();

        book(List.of(hostId), "05:15", "05:45", invitedUser())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SLOT_TAKEN"))
                .andExpect(jsonPath("$.message").value("This time was just taken"))
                .andExpect(jsonPath("$.openSlots", hasSize(87)))
                .andExpect(jsonPath("$.openSlots[*].slotId").value(not(hasItem((int) bookedSlotId))));
    }

    @Test
    void onlyOneOfManySimultaneousClicksGetsTheSlot() throws Exception {
        long hostId = indiaHost(4);
        int people = 20;
        List<String> emails = new ArrayList<>();
        for (int i = 0; i < people; i++) {
            emails.add(invitedUser());
        }
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(people);
        List<Future<Boolean>> results = new ArrayList<>();
        for (String email : emails) {
            Callable<Boolean> attempt = () -> {
                go.await();
                try {
                    bookingService.book(request(List.of(hostId), "05:15", "05:45", email));
                    return true;
                } catch (BookingException e) {
                    assertEquals(BookingException.Reason.SLOT_TAKEN, e.getReason());
                    return false;
                }
            };
            results.add(pool.submit(attempt));
        }
        go.countDown();
        int winners = 0;
        for (Future<Boolean> r : results) {
            winners += r.get(30, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdown();

        assertEquals(1, winners);
        assertEquals(1, bookings.findByHostIdOrderBySlotStartAsc(hostId).stream()
                .filter(b -> b.getStatus() == BookingStatus.CONFIRMED).count());
    }

    @Test
    void dayIsFullOnceTheHostsCapIsReached() throws Exception {
        long hostId = indiaHost(2);
        book(List.of(hostId), "04:30", "05:00", invitedUser()).andExpect(status().isCreated());
        book(List.of(hostId), "05:15", "05:45", invitedUser()).andExpect(status().isCreated());

        book(List.of(hostId), "06:00", "06:30", invitedUser())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DAY_FULL"));
        availableSlots(String.valueOf(hostId), "Europe/London", MONDAY)
                .andExpect(jsonPath("$.slots", empty()));
        availableSlots(String.valueOf(hostId), "Europe/London", "2027-01-12")
                .andExpect(jsonPath("$.slots", hasSize(4)));
    }

    @Test
    void uninvitedEmailsAndSecondBookingsAreRefused() throws Exception {
        long hostId = indiaHost(4);
        String alex = invitedUser();

        book(List.of(hostId), "05:15", "05:45", "stranger@example.com")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        book(List.of(hostId), "04:30", "05:00", alex).andExpect(status().isCreated());
        book(List.of(hostId), "06:00", "06:30", alex)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ALREADY_BOOKED"));
    }

    @Test
    void aHiringDriveLinkGivesTheTimeToAnotherFreeHost() throws Exception {
        long first = indiaHost(4);
        long second = indiaHost(4);
        List<Long> pool = List.of(first, second);
        String pair = first + "," + second;

        availableSlots(pair, "Asia/Kolkata", MONDAY)
                .andExpect(jsonPath("$.hosts", hasSize(2)))
                .andExpect(jsonPath("$.slots", hasSize(4)))
                .andExpect(jsonPath("$.slots[1].hosts", hasSize(2)));

        Number a = bookAndReadHost(pool, invitedUser());
        Number b = bookAndReadHost(pool, invitedUser());
        assertNotEquals(a.longValue(), b.longValue());
        book(pool, "05:15", "05:45", invitedUser())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SLOT_TAKEN"));
        availableSlots(pair, "Asia/Kolkata", MONDAY)
                .andExpect(jsonPath("$.slots[*].localStartTime").value(contains("10:00", "11:30", "12:15")));
    }

    @Test
    void aRescheduleShowsUpInTheHostsBookings() throws Exception {
        long hostId = indiaHost(4);
        String alex = invitedUser();
        book(List.of(hostId), "04:30", "05:00", alex).andExpect(status().isCreated());
        long userId = users.findByEmailIgnoreCase(alex).orElseThrow().getId();
        long newSlotId = slotAt(hostId, "2027-01-11T06:45:00Z").getId();

        mvc.perform(post("/api/hosts/{hostId}/bookings/reschedule", hostId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\": %d, \"newSlotId\": %d}".formatted(userId, newSlotId)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/hosts/{hostId}/bookings", hostId))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].status").value("RESCHEDULED"))
                .andExpect(jsonPath("$[1].status").value("CONFIRMED"))
                .andExpect(jsonPath("$[1].slotStart").value("2027-01-11T06:45:00Z"))
                .andExpect(jsonPath("$[1].rescheduledFromBookingId").isNumber());
        assertEquals(SlotStatus.OPEN, slotAt(hostId, "2027-01-11T04:30:00Z").getStatus());
    }

    @Test
    void badRequestsAreRejected() throws Exception {
        long hostId = indiaHost(4);

        availableSlots(String.valueOf(hostId), "Mars/Olympus", MONDAY)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        availableSlots("abc", "UTC", MONDAY).andExpect(status().isBadRequest());
        availableSlots("999999", "UTC", MONDAY)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("HOST_NOT_FOUND"));
        mvc.perform(post("/book-slot").contentType(MediaType.APPLICATION_JSON).content("""
                {"hostIds": [%d], "date": "%s", "startTime": "05:15", "endTime": "05:45", "timezone": "UTC"}"""
                .formatted(hostId, MONDAY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    private ResultActions availableSlots(String hostIds, String tz, String date) throws Exception {
        return mvc.perform(get("/get-available-slots/{hostIds}", hostIds).param("tz", tz).param("from", date)
                .param("to", date));
    }

    private ResultActions book(List<Long> hostIds, String start, String end, String email) throws Exception {
        return mvc.perform(post("/book-slot").contentType(MediaType.APPLICATION_JSON).content("""
                {"hostIds": %s, "date": "%s", "startTime": "%s", "endTime": "%s",
                 "timezone": "Europe/London", "email": "%s"}""".formatted(hostIds, MONDAY, start, end, email)));
    }

    /** Books Monday 10:45 IST in the host's own timezone and returns the host that got it. */
    private Number bookAndReadHost(List<Long> pool, String email) throws Exception {
        String body = mvc.perform(post("/book-slot").contentType(MediaType.APPLICATION_JSON).content("""
                {"hostIds": %s, "date": "%s", "startTime": "10:45", "endTime": "11:15",
                 "timezone": "Asia/Kolkata", "email": "%s"}""".formatted(pool, MONDAY, email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.hostId");
    }

    private static BookSlotRequest request(List<Long> hostIds, String start, String end, String email) {
        return new BookSlotRequest(hostIds, LocalDate.parse(MONDAY), LocalTime.parse(start), LocalTime.parse(end),
                "Europe/London", email);
    }

    /** Prefilled host with the problem-statement schedule: Mon-Fri 10:00-13:00 IST, 30 min slots, 15 min gap. */
    private long indiaHost(int maxPerDay) {
        long id = IDS.incrementAndGet();
        Host h = new Host();
        h.setId(id);
        h.setName("Host " + id);
        h.setEmail("host" + id + "@example.com");
        h.setTimezone("UTC");
        h.setAvailability(List.of());
        hosts.save(h);
        availability.publish(new PublishAvailabilityRequest(id, "Asia/Kolkata", 30, 15, maxPerDay,
                List.of(new AvailabilityRule(List.of(1, 2, 3, 4, 5), "10:00", "13:00"))));
        return id;
    }

    /** Prefilled invitee. */
    private String invitedUser() {
        long id = IDS.incrementAndGet();
        AppUser u = new AppUser();
        u.setId(id);
        u.setName("Guest " + id);
        u.setEmail("guest" + id + "@example.com");
        users.save(u);
        return u.getEmail();
    }

    private com.example.demo.model.Slot slotAt(long hostId, String startUtc) {
        Instant start = Instant.parse(startUtc);
        return slots.findByHostIdAndEndUtcAfterOrderByStartUtc(hostId, NOW).stream()
                .filter(s -> s.getStartUtc().equals(start))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no slot at " + startUtc));
    }
}
