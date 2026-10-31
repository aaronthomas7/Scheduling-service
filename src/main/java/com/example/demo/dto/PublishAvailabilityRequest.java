package com.example.demo.dto;

import java.util.List;

import com.example.demo.model.AvailabilityRule;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Body of POST /publish-availability. Replaces the host's whole schedule, e.g.
 * <pre>
 * {"hostId": 1, "timezone": "Asia/Kolkata", "slotMinutes": 30, "gapMinutes": 15, "maxPerDay": 4,
 *  "availability": [{"days": [1,2,3,4,5], "start": "10:00", "end": "13:00"}]}
 * </pre>
 * days are ISO weekdays (1 = Monday); start/end are the host's local time.
 */
public record PublishAvailabilityRequest(
        @NotNull Long hostId,
        @NotBlank String timezone,
        @NotNull @Min(5) @Max(480) Integer slotMinutes,
        @Min(0) @Max(240) Integer gapMinutes,
        @NotNull @Min(1) @Max(100) Integer maxPerDay,
        @NotEmpty List<AvailabilityRule> availability) {
}
