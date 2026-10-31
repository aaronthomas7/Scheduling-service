package com.example.demo.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.example.demo.model.Host;

import jakarta.persistence.LockModeType;

public interface HostRepository extends JpaRepository<Host, Long> {

    /**
     * Locks the host row for the rest of the transaction (SELECT ... FOR UPDATE).
     * Taken in the booking transaction so the daily-cap count and the insert cannot interleave.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Host h where h.id = :id")
    Optional<Host> findByIdForUpdate(Long id);
}
