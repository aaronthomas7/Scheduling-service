package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.dto.BookingResponse;
import com.example.demo.dto.RescheduleRequest;
import com.example.demo.exception.BookingException;
import com.example.demo.model.AppUser;
import com.example.demo.model.AvailabilityRule;
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
@Transactional
class RescheduleServiceTest {

    @Autowired RescheduleService service;
    @Autowired HostRepository hosts;
    @Autowired UserRepository users;
    @Autowired SlotRepository slots;
    @Autowired BookingRepository bookings;

    Host host;
    AppUser alice;
    AppUser bob;

    @BeforeEach
    void seed() {
        host = new Host();
        host.setId(1L);
        host.setName("Host");
        host.setEmail("host@example.com");
        host.setTimezone("Asia/Kolkata");
        host.setAvailability(List.of(new AvailabilityRule(List.of(1, 2, 3, 4, 5), "10:00", "13:00")));
        host.setMaxPerDay(4);
        host = hosts.save(host);
        alice = user(1L, "alice@example.com");
        bob = user(2L, "bob@example.com");
    }

    @Test
    void movesBookingToNewSlotAndFreesTheOldOne() {
        Slot oldSlot = slot(days(3), SlotStatus.BOOKED);
        Slot newSlot = slot(days(4), SlotStatus.OPEN);
        Booking old = book(alice, oldSlot);

        BookingResponse r = service.reschedule(1L, new RescheduleRequest(1L, newSlot.getId(), "Europe/London"));

        assertThat(r.slotId()).isEqualTo(newSlot.getId());
        assertThat(r.rescheduledFromBookingId()).isEqualTo(old.getId());
        assertThat(r.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(r.userTimezone()).isEqualTo("Europe/London");
        assertThat(old.getStatus()).isEqualTo(BookingStatus.RESCHEDULED);
        slots.flush();
        bookings.flush();
        assertThat(old.getActiveSlotId()).isNull();
        assertThat(oldSlot.getStatus()).isEqualTo(SlotStatus.OPEN);
        assertThat(newSlot.getStatus()).isEqualTo(SlotStatus.BOOKED);
    }

    @Test
    void rejectsSlotThatIsAlreadyBooked() {
        Slot oldSlot = slot(days(3), SlotStatus.BOOKED);
        Slot taken = slot(days(4), SlotStatus.BOOKED);
        book(alice, oldSlot);
        book(bob, taken);

        assertThatThrownBy(() -> service.reschedule(1L, new RescheduleRequest(1L, taken.getId(), null)))
                .isInstanceOfSatisfying(BookingException.class, e -> assertThat(e.getCode()).isEqualTo("SLOT_TAKEN"));
        assertThat(oldSlot.getStatus()).isEqualTo(SlotStatus.BOOKED);
    }

    @Test
    void rejectsChangeWithinTwoHoursOfTheBookedSlot() {
        Slot oldSlot = slot(Instant.now().plus(90, ChronoUnit.MINUTES), SlotStatus.BOOKED);
        Slot newSlot = slot(days(2), SlotStatus.OPEN);
        book(alice, oldSlot);

        assertThatThrownBy(() -> service.reschedule(1L, new RescheduleRequest(1L, newSlot.getId(), null)))
                .isInstanceOfSatisfying(BookingException.class,
                        e -> assertThat(e.getCode()).isEqualTo("TOO_LATE_TO_CHANGE"));
    }

    @Test
    void rejectsMoveToADayThatIsFull() {
        host.setMaxPerDay(1);
        Slot oldSlot = slot(days(3), SlotStatus.BOOKED);
        Slot fullDayTaken = slot(days(4), SlotStatus.BOOKED);
        Slot fullDayOpen = slot(days(4).plus(1, ChronoUnit.HOURS), SlotStatus.OPEN);
        book(alice, oldSlot);
        book(bob, fullDayTaken);

        assertThatThrownBy(() -> service.reschedule(1L, new RescheduleRequest(1L, fullDayOpen.getId(), null)))
                .isInstanceOfSatisfying(BookingException.class, e -> assertThat(e.getCode()).isEqualTo("DAY_FULL"));
    }

    @Test
    void allowsMoveWithinTheSameDayEvenWhenDayIsFull() {
        host.setMaxPerDay(1);
        Instant day = days(3);
        Slot oldSlot = slot(day, SlotStatus.BOOKED);
        Slot sameDay = slot(day.plus(1, ChronoUnit.HOURS), SlotStatus.OPEN);
        book(alice, oldSlot);

        BookingResponse r = service.reschedule(1L, new RescheduleRequest(1L, sameDay.getId(), null));

        assertThat(r.slotId()).isEqualTo(sameDay.getId());
    }

    @Test
    void failsWhenUserHasNoUpcomingBooking() {
        Slot newSlot = slot(days(2), SlotStatus.OPEN);

        assertThatThrownBy(() -> service.reschedule(1L, new RescheduleRequest(1L, newSlot.getId(), null)))
                .isInstanceOfSatisfying(BookingException.class, e -> assertThat(e.getCode()).isEqualTo("NOT_FOUND"));
    }

    @Test
    void rejectsSlotOfAnotherHost() {
        Host other = new Host();
        other.setId(2L);
        other.setName("Other");
        other.setEmail("other@example.com");
        other.setTimezone("UTC");
        other.setAvailability(List.of());
        other = hosts.save(other);
        Slot foreign = new Slot();
        foreign.setHost(other);
        foreign.setStartUtc(days(3));
        foreign.setEndUtc(days(3).plus(30, ChronoUnit.MINUTES));
        foreign.setLocalDate(LocalDate.ofInstant(days(3), ZoneOffset.UTC));
        slots.save(foreign);
        book(alice, slot(days(2), SlotStatus.BOOKED));

        assertThatThrownBy(() -> service.reschedule(1L, new RescheduleRequest(1L, foreign.getId(), null)))
                .isInstanceOfSatisfying(BookingException.class, e -> assertThat(e.getCode()).isEqualTo("NOT_FOUND"));
    }

    private AppUser user(Long id, String email) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setName(email);
        u.setEmail(email);
        u.setHost(host);
        return users.save(u);
    }

    private static Instant days(int n) {
        return Instant.now().truncatedTo(ChronoUnit.DAYS).plus(n, ChronoUnit.DAYS).plus(10, ChronoUnit.HOURS);
    }

    private Slot slot(Instant start, SlotStatus status) {
        Slot s = new Slot();
        s.setHost(host);
        s.setStartUtc(start);
        s.setEndUtc(start.plus(30, ChronoUnit.MINUTES));
        s.setLocalDate(LocalDate.ofInstant(start, ZoneOffset.UTC));
        s.setStatus(status);
        return slots.save(s);
    }

    private Booking book(AppUser user, Slot slot) {
        Booking b = new Booking();
        b.setSlot(slot);
        b.setHost(host);
        b.setUser(user);
        b.setSlotStart(slot.getStartUtc());
        b.setSlotEnd(slot.getEndUtc());
        b.setStatus(BookingStatus.CONFIRMED);
        return bookings.saveAndFlush(b);
    }
}
