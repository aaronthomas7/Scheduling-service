package com.example.demo.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.dto.HostBookingResponse;
import com.example.demo.exception.HostNotFoundException;
import com.example.demo.model.Booking;
import com.example.demo.repository.BookingRepository;
import com.example.demo.repository.HostRepository;

/** A host's bookings as they stand now: new bookings and reschedules show up here straight away. */
@Service
public class HostBookingsService {

    private final HostRepository hosts;
    private final BookingRepository bookings;

    public HostBookingsService(HostRepository hosts, BookingRepository bookings) {
        this.hosts = hosts;
        this.bookings = bookings;
    }

    @Transactional(readOnly = true)
    public List<HostBookingResponse> list(Long hostId) {
        if (!hosts.existsById(hostId)) {
            throw new HostNotFoundException(hostId);
        }
        return bookings.findByHostIdOrderBySlotStartAsc(hostId).stream().map(HostBookingsService::toResponse).toList();
    }

    private static HostBookingResponse toResponse(Booking b) {
        return new HostBookingResponse(b.getId(), b.getSlot().getId(), b.getSlotStart(), b.getSlotEnd(), b.getStatus(),
                b.getUser().getId(), b.getUser().getName(), b.getUser().getEmail(), b.getUserTimezone(),
                b.isConflictFlag(), b.getRescheduledFrom() == null ? null : b.getRescheduledFrom().getId(),
                b.getCreatedAt());
    }
}
