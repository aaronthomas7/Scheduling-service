package com.example.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.model.AppUser;
import com.example.demo.model.AvailabilityRule;
import com.example.demo.model.Booking;
import com.example.demo.model.Host;
import com.example.demo.model.Slot;
import com.example.demo.model.SlotStatus;
import com.example.demo.repository.BookingRepository;
import com.example.demo.repository.HostRepository;
import com.example.demo.repository.SlotRepository;
import com.example.demo.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RescheduleApiTest {

    @Autowired MockMvc mvc;
    @Autowired HostRepository hosts;
    @Autowired UserRepository users;
    @Autowired SlotRepository slots;
    @Autowired BookingRepository bookings;

    @Test
    void reschedulesOverHttp() throws Exception {
        Fixture f = fixture();

        mvc.perform(post("/api/hosts/10/bookings/reschedule").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":10,\"newSlotId\":" + f.open.getId() + ",\"userTimezone\":\"Europe/London\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotId").value(f.open.getId()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.rescheduledFromBookingId").value(f.old.getId()));
    }

    @Test
    void slotTakenReturns409WithRemainingOpenSlots() throws Exception {
        Fixture f = fixture();

        mvc.perform(post("/api/hosts/10/bookings/reschedule").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":10,\"newSlotId\":" + f.taken.getId() + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SLOT_TAKEN"))
                .andExpect(jsonPath("$.message").value("This time was just taken"))
                .andExpect(jsonPath("$.openSlots[0].slotId").value(f.open.getId()));
    }

    private record Fixture(Booking old, Slot open, Slot taken) {
    }

    private Fixture fixture() {
        Host h = new Host();
        h.setId(10L);
        h.setName("Host");
        h.setEmail("h10@example.com");
        h.setTimezone("Asia/Kolkata");
        h.setAvailability(List.of(new AvailabilityRule(List.of(1), "10:00", "13:00")));
        h = hosts.save(h);
        AppUser u = new AppUser();
        u.setId(10L);
        u.setName("U");
        u.setEmail("u10@example.com");
        u.setHost(h);
        u = users.save(u);
        Slot oldSlot = slot(h, 3, SlotStatus.BOOKED);
        Slot open = slot(h, 4, SlotStatus.OPEN);
        Slot taken = slot(h, 5, SlotStatus.BOOKED);
        Booking b = new Booking();
        b.setSlot(oldSlot);
        b.setHost(h);
        b.setUser(u);
        b.setSlotStart(oldSlot.getStartUtc());
        b.setSlotEnd(oldSlot.getEndUtc());
        bookings.saveAndFlush(b);
        return new Fixture(b, open, taken);
    }

    private Slot slot(Host h, int plusDays, SlotStatus status) {
        Instant start = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(plusDays, ChronoUnit.DAYS)
                .plus(10, ChronoUnit.HOURS);
        Slot s = new Slot();
        s.setHost(h);
        s.setStartUtc(start);
        s.setEndUtc(start.plus(30, ChronoUnit.MINUTES));
        s.setLocalDate(LocalDate.ofInstant(start, ZoneOffset.UTC));
        s.setStatus(status);
        return slots.save(s);
    }
}
