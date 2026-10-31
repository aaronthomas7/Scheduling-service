package com.example.demo.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.dto.BookingResponse;
import com.example.demo.dto.RescheduleRequest;
import com.example.demo.exception.BookingException;
import com.example.demo.model.Booking;
import com.example.demo.model.BookingStatus;
import com.example.demo.model.Host;
import com.example.demo.model.Slot;
import com.example.demo.model.SlotStatus;
import com.example.demo.repository.BookingRepository;
import com.example.demo.repository.HostRepository;
import com.example.demo.repository.SlotRepository;

@Service
public class RescheduleService {

    /** A booking can be changed up to this long before it starts. */
    static final Duration CHANGE_CUTOFF = Duration.ofHours(2);

    private final HostRepository hostRepository;
    private final SlotRepository slotRepository;
    private final BookingRepository bookingRepository;
    private final Clock clock;

    public RescheduleService(HostRepository hostRepository, SlotRepository slotRepository,
            BookingRepository bookingRepository, Clock clock) {
        this.hostRepository = hostRepository;
        this.slotRepository = slotRepository;
        this.bookingRepository = bookingRepository;
        this.clock = clock;
    }

    /**
     * Moves the user's upcoming confirmed booking with the host to another slot.
     * The new booking is created and the old one released in one transaction, so the user
     * is never left without a slot and the old slot only reopens if the new one was won.
     */
    @Transactional
    public BookingResponse reschedule(Long hostId, RescheduleRequest request) {
        if (request == null || request.userId() == null || request.newSlotId() == null) {
            throw BookingException.badRequest("userId and newSlotId are required");
        }
        Instant now = clock.instant();

        // Same lock order as any booking path: host row, then slot row.
        Host host = hostRepository.findByIdForUpdate(hostId)
                .orElseThrow(() -> BookingException.notFound("Host not found"));

        Booking old = bookingRepository
                .findFirstByHostIdAndUserIdAndStatusAndSlotStartAfterOrderBySlotStart(
                        hostId, request.userId(), BookingStatus.CONFIRMED, now)
                .orElseThrow(() -> BookingException.notFound("No upcoming booking with this host to reschedule"));

        if (now.isAfter(old.getSlotStart().minus(CHANGE_CUTOFF))) {
            throw BookingException.tooLate("Bookings can only be changed up to 2 hours before the slot");
        }

        Slot newSlot = slotRepository.findByIdForUpdate(request.newSlotId())
                .filter(s -> s.getHost().getId().equals(hostId))
                .orElseThrow(() -> BookingException.notFound("Slot not found for this host"));
        Slot oldSlot = old.getSlot();

        if (newSlot.getId().equals(oldSlot.getId())) {
            throw BookingException.badRequest("That is already your booked slot");
        }
        if (newSlot.getStatus() != SlotStatus.OPEN) {
            throw BookingException.slotTaken(hostId);
        }
        if (!newSlot.getStartUtc().isAfter(now)) {
            throw BookingException.badRequest("Slot is in the past");
        }
        // Moving within the same day keeps the count unchanged; moving to another day adds one there.
        if (!newSlot.getLocalDate().equals(oldSlot.getLocalDate())
                && bookingRepository.countByHostAndLocalDate(hostId, newSlot.getLocalDate(),
                        BookingStatus.CONFIRMED) >= host.getMaxPerDay()) {
            throw BookingException.dayFull(hostId);
        }

        oldSlot.setStatus(SlotStatus.OPEN);
        old.setStatus(BookingStatus.RESCHEDULED);
        old.setCancelledAt(now);

        newSlot.setStatus(SlotStatus.BOOKED);
        Booking next = new Booking();
        next.setSlot(newSlot);
        next.setHost(old.getHost());
        next.setUser(old.getUser());
        next.setSlotStart(newSlot.getStartUtc());
        next.setSlotEnd(newSlot.getEndUtc());
        next.setStatus(BookingStatus.CONFIRMED);
        next.setUserTimezone(request.userTimezone() != null ? request.userTimezone() : old.getUserTimezone());
        next.setRescheduledFrom(old);
        // Kept so the calendar-invite worker can update the existing events rather than create new ones.
        next.setHostEventId(old.getHostEventId());
        next.setUserEventId(old.getUserEventId());
        next.setCreatedAt(now);
        bookingRepository.save(next);

        return new BookingResponse(next.getId(), hostId, next.getUser().getId(), newSlot.getId(),
                next.getSlotStart(), next.getSlotEnd(), next.getStatus(), next.getUserTimezone(), old.getId());
    }
}
