package com.example.demo.model;

import java.util.List;

/**
 * One weekly availability window in the host's local time.
 * days: ISO weekday numbers (1 = Monday ... 7 = Sunday); start/end: "HH:mm".
 */
public record AvailabilityRule(List<Integer> days, String start, String end) {
}
