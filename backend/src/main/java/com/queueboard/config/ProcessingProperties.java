package com.queueboard.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning knobs for the simulated job processor, bound from {@code queueboard.processing.*}.
 * Keeping simulation behavior in configuration (not constants) makes failure paths
 * demonstrable in the UI and deterministic in tests (see ADR-0004).
 *
 * <p>Failure triggers are substring markers matched against the payload
 * (case-sensitive; {@code null} payload never fails). {@code flakyMarker} is checked
 * before {@code failMarker}, so a payload containing both behaves as flaky:
 * <ul>
 *   <li>{@code failMarker} — every attempt fails (demonstrates attempts exhaustion);</li>
 *   <li>{@code flakyMarker} — only the first attempt fails (a retry demonstrably recovers).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "queueboard.processing")
public record ProcessingProperties(
        Duration pollInterval,
        Duration minDuration,
        Duration maxDuration,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("fail") String failMarker,
        @DefaultValue("flaky") String flakyMarker,
        @DefaultValue("2") int workerConcurrency) {
}
