# ADR-0004: Simulated processing via an in-process async worker

- Status: Accepted
- Date: 2026-11-09

## Context

The assignment explicitly allows simulated processing. Options: (a) in-process async
worker, (b) scheduled poller, (c) external broker (overkill).

## Decision

An in-process worker (`job/processing`) claims PENDING jobs and simulates work:
configurable duration and failure behavior via `queueboard.processing.*` properties.
Deterministic failure triggers (e.g. by job type or payload marker) make error paths
demonstrable and testable, not just random.

Optimistic locking (`@Version` on `Job`) protects against races between the worker and
user actions (e.g. retry) — the losing write retries or fails loudly, never silently
overwrites.

## Consequences

- Single-instance only; a real system would use a durable queue + competing consumers.
  Accepted for a 4–8h prototype and documented as a known limitation.
- Failure simulation is a feature of the demo, controllable in tests.
