# ADR-0005: UI state synchronization via polling (not SSE/WebSocket)

- Status: Accepted
- Date: 2026-11-09

## Context

The interface must reflect status changes and stay consistent with the backend.
Push (SSE/WebSocket) gives lower latency but adds connection lifecycle management,
reconnection logic, and test complexity — disproportionate for this scope.

## Decision

The Angular jobs store polls the list/detail endpoints on a short interval while
non-terminal jobs are visible, and stops when all visible jobs are terminal.
The backend remains the single source of truth; the store never fabricates a state.

## Consequences

- Bounded staleness (one poll interval); acceptable for a monitoring board.
- Trivial to replace with SSE later — the store is the only place that knows how
  fresh data arrives.
