package com.example.demo.repository;

import java.time.Instant;
import java.util.List;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.example.demo.model.Slot;
import com.example.demo.model.SlotStatus;

import jakarta.persistence.LockModeType;

public interface SlotRepository extends JpaRepository<Slot, Long> {

    List<Slot> findByHostIdAndStatusAndStartUtcAfterOrderByStartUtc(Long hostId, SlotStatus status, Instant after);

    /** Slots that haven't finished yet, including one in progress. */
    List<Slot> findByHostIdAndEndUtcAfterOrderByStartUtc(Long hostId, Instant after);

    /** Slots in a status that overlap [from, to). */
    @Query("select s from Slot s where s.host.id = :hostId and s.status = :status "
            + "and s.startUtc < :to and s.endUtc > :from")
    List<Slot> findOverlapping(Long hostId, SlotStatus status, Instant from, Instant to);

    /** Row-locks the slot (SELECT ... FOR UPDATE) for the rest of the transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Slot s where s.id = :id")
    Optional<Slot> findByIdForUpdate(Long id);
}
