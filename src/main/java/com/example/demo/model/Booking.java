package com.example.demo.model;

import java.time.Instant;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A user's booking of a slot.
 * The "one confirmed booking per slot" rule is a partial unique index that JPA cannot
 * express; MySQL has no partial indexes, so
 * {@code activeSlotId} (= slot id while CONFIRMED, else NULL) carries a unique index.
 * NULLs are not equal in a unique index, so any number of cancelled rows are allowed.
 */
@Entity
@Table(name = "bookings", indexes = {
        @Index(name = "idx_bookings_host_start", columnList = "host_id, slot_start"),
        @Index(name = "idx_bookings_user", columnList = "user_id")
})
@Getter
@Setter
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false)
    private Slot slot;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_id", nullable = false)
    private Host host;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    /** Copied from the slot so history survives if slots are regenerated. */
    @Column(name = "slot_start", nullable = false)
    private Instant slotStart;

    @Column(name = "slot_end", nullable = false)
    private Instant slotEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status = BookingStatus.CONFIRMED;

    @Column(name = "user_timezone")
    private String userTimezone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rescheduled_from")
    private Booking rescheduledFrom;

    /** Set when the host adds a calendar event over an already-confirmed booking. */
    @Column(name = "conflict_flag", nullable = false)
    private boolean conflictFlag = false;

    @Column(name = "manage_token_hash")
    private String manageTokenHash;

    @Column(name = "host_event_id")
    private String hostEventId;

    @Column(name = "user_event_id")
    private String userEventId;

    /** Equals slot id while status is CONFIRMED, otherwise null. Unique: one active booking per slot. */
    @Column(name = "active_slot_id", unique = true)
    private Long activeSlotId;

    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @PrePersist
    @PreUpdate
    void syncActiveSlot() {
        activeSlotId = status == BookingStatus.CONFIRMED && slot != null ? slot.getId() : null;
    }
}
