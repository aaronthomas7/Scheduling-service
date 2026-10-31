package com.example.demo.dto;

/**
 * @param userId       the person rescheduling (they already hold a confirmed booking with the host)
 * @param newSlotId    the slot they picked on the host's booking page
 * @param userTimezone optional IANA zone of the viewer; kept from the old booking when absent
 */
public record RescheduleRequest(Long userId, Long newSlotId, String userTimezone) {
}
