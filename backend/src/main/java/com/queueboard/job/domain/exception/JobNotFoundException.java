package com.queueboard.job.domain.exception;

/** Thrown when a job id does not exist. Mapped to HTTP 404. */
public class JobNotFoundException extends RuntimeException {

    public JobNotFoundException(String message) {
        super(message);
    }
}
