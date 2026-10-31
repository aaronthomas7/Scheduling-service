package com.example.demo.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Same shape as ApiExceptionHandler.ErrorBody; openSlots is filled when the client should re-pick a time. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String error, String message, List<SlotResponse> openSlots) {
}
