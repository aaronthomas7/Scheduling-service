package com.example.demo.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.example.demo.dto.BookSlotRequest;
import com.example.demo.dto.BookingResponse;
import com.example.demo.exception.BookingException;
import com.example.demo.model.AppUser;
import com.example.demo.model.Slot;
import com.example.demo.model.SlotStatus;
import com.example.demo.repository.HostRepository;
import com.example.demo.repository.SlotRepository;
import com.example.demo.repository.UserRepository;

/**
 * Turns a /book-slot request into a reservation: resolves the viewer-local time and the invited user,
 * then tries each host in the link that still has that time open. Each attempt is its own transaction
 * in {@link SlotReservationService}, so losing a race on one host doesn't hold locks while trying the next.
 */
@Service
public class BookingService {

    private final UserRepository users;
    private final HostRepository hosts;
    private final SlotRepository slots;
    private final SlotReservationService reservations;

    public BookingService(UserRepository users, HostRepository hosts, SlotRepository slots,
            SlotReservationService reservations) {
        this.users = users;
        this.hosts = hosts;
        this.slots = slots;
        this.reservations = reservations;
    }

    public BookingResponse book(BookSlotRequest r) {
        ZoneId zone = TimeZones.parse(r.timezone())
                .orElseThrow(() -> BookingException.badRequest("Unknown timezone '" + r.timezone() + "'"));
        Instant start = ZonedDateTime.of(r.date(), r.startTime(), zone).toInstant();
        // An end at or before the start is on the next day (e.g. 23:45-00:15).
        LocalDate endDate = r.endTime().isAfter(r.startTime()) ? r.date() : r.date().plusDays(1);
        Instant end = ZonedDateTime.of(endDate, r.endTime(), zone).toInstant();

        String email = r.email().trim();
        AppUser user = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> BookingException.notFound("No invited user with email " + email));

        List<Long> hostIds = new ArrayList<>(new LinkedHashSet<>(r.hostIds()));
        // Random order spreads a burst over a hiring-drive pool instead of queueing everyone on one host's lock.
        Collections.shuffle(hostIds);

        BookingException refusal = null;
        boolean anyHost = false;
        for (Long hostId : hostIds) {
            if (!hosts.existsById(hostId)) {
                continue;
            }
            anyHost = true;
            Optional<Slot> slot = slots.findByHostIdAndStartUtcAndEndUtc(hostId, start, end);
            if (slot.isEmpty() || slot.get().getStatus() != SlotStatus.OPEN) {
                refusal = BookingException.slotTaken(hostId);
                continue;
            }
            try {
                return reservations.reserve(hostId, slot.get().getId(), user.getId(), zone.getId());
            } catch (BookingException e) {
                if (e.getReason() != BookingException.Reason.SLOT_TAKEN
                        && e.getReason() != BookingException.Reason.DAY_FULL) {
                    throw e;
                }
                refusal = e;
            } catch (DataIntegrityViolationException e) {
                // The unique active-booking index caught a race the locks didn't: same as "just taken".
                refusal = BookingException.slotTaken(hostId);
            }
        }
        if (!anyHost) {
            throw BookingException.notFound("Host not found");
        }
        throw refusal;
    }
}
