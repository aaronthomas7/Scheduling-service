package com.example.demo.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.model.Slot;
import com.example.demo.model.SlotStatus;

public interface SlotRepository extends JpaRepository<Slot, Long> {

    List<Slot> findByHostIdAndStatusAndStartUtcAfterOrderByStartUtc(Long hostId, SlotStatus status, Instant after);

    /** Slots that haven't finished yet, including one in progress. */
    List<Slot> findByHostIdAndEndUtcAfterOrderByStartUtc(Long hostId, Instant after);
}
