package com.example.demo.model;

import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "host")
@Getter
@Setter
public class Host {

    @Id
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    /** IANA zone id, e.g. Asia/Kolkata. Availability rules are interpreted in this zone. */
    @Column(nullable = false)
    private String timezone;

    @Column(name = "slot_minutes", nullable = false)
    private int slotMinutes = 30;

    @Column(name = "gap_minutes", nullable = false)
    private int gapMinutes = 0;

    @Column(name = "max_per_day", nullable = false)
    private int maxPerDay = 4;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<AvailabilityRule> availability;

    @Column(name = "calendar_provider")
    private String calendarProvider;

    @Column(name = "calendar_ref")
    private String calendarRef;
}
