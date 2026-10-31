package com.example.demo.model;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/** One bookable time slot per row. Generated from the host's availability rules. */
@Entity
@Table(name = "slots",
        uniqueConstraints = @UniqueConstraint(columnNames = { "host_id", "start_utc" }),
        indexes = {
                @Index(name = "idx_slots_host_status_start", columnList = "host_id, status, start_utc"),
                @Index(name = "idx_slots_host_local_date", columnList = "host_id, local_date")
        })
@Getter
@Setter
public class Slot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_id", nullable = false)
    private Host host;

    @Column(name = "start_utc", nullable = false)
    private Instant startUtc;

    @Column(name = "end_utc", nullable = false)
    private Instant endUtc;

    /** Date in the host's timezone; used for the max-bookings-per-day cap. */
    @Column(name = "local_date", nullable = false)
    private LocalDate localDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SlotStatus status = SlotStatus.OPEN;

    @Column(nullable = false)
    private int maxInterviees = 2;

    @Column(name = "count", nullable = false)
    private int count = 0;
}
