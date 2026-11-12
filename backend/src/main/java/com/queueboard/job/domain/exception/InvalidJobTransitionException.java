package com.queueboard.job.domain.exception;

/** Thrown on an illegal lifecycle transition. Mapped to HTTP 409. */
public class InvalidJobTransitionException extends RuntimeException {

    public InvalidJobTransitionException(String message) {
        super(message);
    }
}
