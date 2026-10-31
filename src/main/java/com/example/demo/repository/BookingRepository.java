package com.example.demo.repository;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.example.demo.model.Booking;
import com.example.demo.model.BookingStatus;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    List<Booking> findByUserId(Long userId);

    /** The user's next upcoming booking with this host in the given status. */
    Optional<Booking> findFirstByHostIdAndUserIdAndStatusAndSlotStartAfterOrderBySlotStart(
            Long hostId, Long userId, BookingStatus status, Instant after);

    /** Confirmed bookings for a host on a host-local date; compared with Host.maxPerDay. */
    @Query("select count(b) from Booking b where b.host.id = :hostId "
            + "and b.slot.localDate = :localDate and b.status = :status")
    long countByHostAndLocalDate(Long hostId, LocalDate localDate, BookingStatus status);

    /** Any booking (even cancelled) pointing at the slot; such a slot row can't be deleted. */
    boolean existsBySlotId(Long slotId);
}
