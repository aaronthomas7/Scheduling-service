package com.example.demo.dto;

import java.time.Instant;

public record SlotResponse(Long slotId, Instant startUtc, Instant endUtc) {
}
