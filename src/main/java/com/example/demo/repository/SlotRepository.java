package com.example.demo.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.example.demo.model.Slot;
import com.example.demo.model.SlotStatus;

public interface SlotRepository extends JpaRepository<Slot, Long> {

    List<Slot> findByHostIdAndStatusAndStartUtcAfterOrderByStartUtc(Long hostId, SlotStatus status, Instant after);

    /** Slots that haven't finished yet, including one in progress. */
    List<Slot> findByHostIdAndEndUtcAfterOrderByStartUtc(Long hostId, Instant after);

    /** Slots in a status that overlap [from, to). */
    @Query("select s from Slot s where s.host.id = :hostId and s.status = :status "
            + "and s.startUtc < :to and s.endUtc > :from")
    List<Slot> findOverlapping(Long hostId, SlotStatus status, Instant from, Instant to);
}
