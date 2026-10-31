package com.example.demo.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.dto.BookingResponse;
import com.example.demo.exception.BookingException;
import com.example.demo.model.Booking;
import com.example.demo.model.BookingStatus;
import com.example.demo.model.Host;
import com.example.demo.model.Slot;
import com.example.demo.model.SlotStatus;
import com.example.demo.repository.BookingRepository;
import com.example.demo.repository.HostRepository;
import com.example.demo.repository.SlotRepository;
import com.example.demo.repository.UserRepository;

/**
 * Books one slot for one user, in one transaction. This is the only place a new booking is written.
 *
 * Takes the same locks in the same order as RescheduleService (host row, then slot row), so two people
 * clicking the same time are serialised: the first commits, the second sees the slot BOOKED and gets
 * SLOT_TAKEN. The daily cap is counted under the host lock too. If anything ever slipped past the locks,
 * the unique index on bookings.active_slot_id rejects the second confirmed booking.
 */
@Service
public class SlotReservationService {

    private final HostRepository hosts;
    private final SlotRepository slots;
    private final BookingRepository bookings;
    private final UserRepository users;
    private final Clock clock;

    public SlotReservationService(HostRepository hosts, SlotRepository slots, BookingRepository bookings,
            UserRepository users, Clock clock) {
        this.hosts = hosts;
        this.slots = slots;
        this.bookings = bookings;
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public BookingResponse reserve(Long hostId, Long slotId, Long userId, String userTimezone) {
        Instant now = clock.instant();
        Host host = hosts.findByIdForUpdate(hostId).orElseThrow(() -> BookingException.notFound("Host not found"));
        Slot slot = slots.findByIdForUpdate(slotId)
                .filter(s -> s.getHost().getId().equals(hostId))
                .orElseThrow(() -> BookingException.notFound("Slot not found for this host"));

        if (slot.getStatus() != SlotStatus.OPEN) {
            throw BookingException.slotTaken(hostId);
        }
        if (!slot.getStartUtc().isAfter(now)) {
            throw BookingException.badRequest("Slot is in the past");
        }
        // One upcoming booking per person per host; reschedule moves "the" booking, so keep it unambiguous.
        if (bookings.findFirstByHostIdAndUserIdAndStatusAndSlotStartAfterOrderBySlotStart(
                hostId, userId, BookingStatus.CONFIRMED, now).isPresent()) {
            throw BookingException.alreadyBooked();
        }
        if (bookings.countByHostAndLocalDate(hostId, slot.getLocalDate(), BookingStatus.CONFIRMED)
                >= host.getMaxPerDay()) {
            throw BookingException.dayFull(hostId);
        }

        slot.setStatus(SlotStatus.BOOKED);
        Booking booking = new Booking();
        booking.setSlot(slot);
        booking.setHost(host);
        booking.setUser(users.getReferenceById(userId));
        booking.setSlotStart(slot.getStartUtc());
        booking.setSlotEnd(slot.getEndUtc());
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setUserTimezone(userTimezone);
        booking.setCreatedAt(now);
        bookings.saveAndFlush(booking);

        return new BookingResponse(booking.getId(), hostId, userId, slot.getId(), booking.getSlotStart(),
                booking.getSlotEnd(), booking.getStatus(), booking.getUserTimezone(), null);
    }
}
