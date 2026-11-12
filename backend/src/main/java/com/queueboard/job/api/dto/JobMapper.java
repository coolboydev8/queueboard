package com.queueboard.job.api.dto;

import com.queueboard.job.domain.Job;

/**
 * Entity &lt;-&gt; DTO mapping in one place (hand-written; MapStruct would be overkill here).
 */
public final class JobMapper {

    private JobMapper() {
    }

    public static JobResponse toResponse(Job job) {
        return new JobResponse(
                job.getId(),
                job.getName(),
                job.getType(),
                job.getPayload(),
                job.getStatus(),
                job.getAttempts(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getVersion());
    }
}
