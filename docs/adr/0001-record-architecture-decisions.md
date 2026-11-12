# ADR-0001: Record architecture decisions

- Status: Accepted
- Date: 2026-11-12

## Context

Even in a small prototype, reviewers (and future teammates) need the *why* behind
non-obvious choices. Written, versioned decisions keep interfaces and expectations stable.

## Decision

Keep lightweight ADRs in `docs/adr/`, one decision per file, numbered. Supersede rather
than rewrite.

## Consequences

Small documentation overhead; in exchange, trade-offs are explicit and discussable in the
follow-up interview.
