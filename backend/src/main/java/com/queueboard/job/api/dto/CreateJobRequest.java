package com.queueboard.job.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound DTO — validation lives at the edge (Bean Validation), the entity is never
 * bound from the request body. Mirrors #/components/schemas/CreateJobRequest:
 * {@code type} is a free-form identifier and {@code payload} is optional, opaque text.
 */
public record CreateJobRequest(
        @NotBlank(message = "Name must not be blank")
        @Size(max = 100, message = "Name must be at most 100 characters")
        String name,

        @NotBlank(message = "Type must not be blank")
        @Size(max = 100, message = "Type must be at most 100 characters")
        String type,

        @Size(max = 10_000, message = "Payload must be at most 10000 characters")
        String payload) {
}
