# ADR-0003: Job lifecycle as an explicit state machine owned by the domain

- Status: Accepted
- Date: 2026-11-12

## Context

Requirements: jobs move through a defined lifecycle; the UI must never claim success when
the backend reports failure or an unknown state; failed jobs can be retried under defined
rules. Transition logic scattered across controller/worker code is the classic source of
inconsistent states.

## Decision

- `JobStatus` (PENDING, PROCESSING, SUCCEEDED, FAILED) declares its own allowed
  transitions; `JobService` is the only place a transition is applied.
- Every transition stamps a timestamp and (on failure) a captured error message —
  a minimal audit trail.
- Illegal transitions throw a domain exception → HTTP 409 with a Problem Details body.
- The frontend renders exactly the status the backend returns; unknown values render as
  an explicit "unknown" state, never as success.

## Consequences

- Transition rules are unit-testable in isolation (bottom of the test pyramid).
- Retry rules live beside the state machine, not in the controller.
