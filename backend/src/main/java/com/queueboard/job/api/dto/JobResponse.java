package com.queueboard.job.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.queueboard.job.domain.JobStatus;

/**
 * Outbound DTO mirroring #/components/schemas/Job in the OpenAPI contract.
 * Keeps the wire format decoupled from the JPA entity. {@link Instant} fields
 * serialize as ISO-8601 UTC strings; nullable fields serialize as JSON {@code null},
 * both per the contract.
 */
public record JobResponse(
        UUID id,
        String name,
        String type,
        String payload,
        JobStatus status,
        int attempts,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        long version) {
}
