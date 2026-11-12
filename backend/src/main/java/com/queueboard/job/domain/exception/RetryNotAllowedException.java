package com.queueboard.job.domain.exception;

/** Thrown when retry rules forbid re-queuing (wrong state or attempts exhausted). Mapped to HTTP 409. */
public class RetryNotAllowedException extends RuntimeException {

    public RetryNotAllowedException(String message) {
        super(message);
    }
}
