# QueueBoard — Design Note

## 1. Architecture overview

```
┌─────────────────────┐        HTTP/JSON (OpenAPI contract)        ┌──────────────────────────┐
│  Angular 21 SPA     │  ────────────────────────────────────────▶ │  Spring Boot 4 API       │
│  features/jobs      │  ◀──────────  polling (ADR-0005) ────────  │  controller → service →  │
│  signals store      │                                            │  repository (JPA / H2)   │
└─────────────────────┘                                            └───────────┬──────────────┘
                                                                               │ async
                                                                   ┌───────────▼──────────────┐
                                                                   │  Simulated JobProcessor  │
                                                                   │  (claims PENDING jobs,   │
                                                                   │   drives the lifecycle)  │
                                                                   └──────────────────────────┘
```

- **Contract-first**: `docs/api/openapi.yaml` is the agreed interface; both sides build
  against it (ADR-0001 rationale: decisions are written down and versioned).
- **Backend layering**: thin controllers, business rules in services, Spring Data JPA for
  persistence (ADR-0002). Packaged by feature (`job/`) with layers inside, so the feature
  is cohesive but the layering stays enforceable (ArchUnit test).
- **Lifecycle as an explicit state machine**: allowed transitions are data, not scattered
  `if`s (ADR-0003). The backend is the single source of truth for job state — the UI never
  infers or optimistically claims success.
- **Frontend**: zoneless Angular, signals, and one route-scoped `JobsStore`. Pages are thin.
  Presentational components (`job-status-badge`, `lifecycle-track`, `job-form`, shared alert /
  empty / progress / timestamp) do not call HTTP. `resource()` loads list and detail inside the
  store; `JobsApi` stays a thin client of the OpenAPI operations. Poll every 2s while a visible
  job is non-terminal, then stop (ADR-0005). A failed refresh keeps the last payload and is
  marked stale. Unknown status strings render as Unknown, with a dashed badge, never as success.
  Retry is offered only from `FAILED`; the button shows the body or the problem the server
  returns. The retry ceiling (`max-attempts`) is not copied into the client. The create form is
  a typed reactive form whose limits match the contract; type is free text because the contract
  publishes no enum. Signal forms were skipped — they are still experimental in Angular 21.
- **Simulated processing**: an async worker claims `PENDING` jobs and simulates
  duration/failure via configuration (ADR-0004), so failure paths are testable and
  demonstrable on demand.

## 2. Job lifecycle

```
            submit                 claim                    success
  (create) ───────▶ PENDING ───────────────▶ PROCESSING ─────────────▶ SUCCEEDED
                       ▲                         │
                       │ retry (rules permit)    │ failure (error captured)
                       └──────────── FAILED ◀────┘
```

**Transition rules** (enforced inside the `Job` aggregate; `JobService` is the only caller):

- Exactly four legal transitions: `PENDING → PROCESSING` (claim), `PROCESSING → SUCCEEDED`,
  `PROCESSING → FAILED`, `FAILED → PENDING` (retry). Everything else — including every
  self-transition — throws and maps to HTTP 409.
- Only the worker claims and completes jobs; only a user request triggers a retry.
- Timestamps: `createdAt` is stamped once at submission; `startedAt` restamps on every claim;
  `completedAt` is non-null exactly when the state is terminal (`SUCCEEDED`/`FAILED`).
- `attempts` counts *started* attempts — it increments only on claim.
- `errorMessage` is captured on failure and survives a retry (the board can still show why the
  last attempt failed) until the next attempt starts, which clears it.

**Retry rules** (`queueboard.processing.max-attempts`, default 3 — owned by the backend; the UI
never duplicates the rule):

| Job state | Rule | Result |
| --- | --- | --- |
| `FAILED`, attempts < max | re-queue | 200, job back in `PENDING` |
| `FAILED`, attempts ≥ max | exhausted | 409 |
| `PENDING` after an earlier retry (attempts > 0) | idempotent replay | 200 no-op — never double-queues |
| `PENDING` never attempted, `PROCESSING`, `SUCCEEDED` | nothing to retry | 409 with a state-specific message |

The idempotency check rests on an invariant rather than a flag: a `PENDING` job with
`attempts > 0` can *only* exist via `FAILED → retry`, because attempts grow only on claim and
`SUCCEEDED` has no outgoing edge.

**Deterministic failure simulation** (ADR-0004): payload markers, configurable via
`queueboard.processing.*` — a payload containing `fail` fails every attempt (demonstrates
exhaustion), a payload containing `flaky` fails only the first attempt (demonstrates a retry
that recovers). Everything else succeeds after a simulated 2–6 s of work.

## 3. Key decisions & trade-offs (summaries — details in ADRs)

| Decision | Choice | Trade-off accepted |
| --- | --- | --- |
| Package layout | By feature, layered inside | Slightly unusual vs. pure layer packages; pays off in cohesion |
| State updates to UI | Polling | Simpler + stateless vs. SSE/WebSocket push; latency bounded by interval |
| Concurrency control | Optimistic locking (`@Version`) | Retry-on-conflict needed; avoids lost updates between processor and user actions |
| Retry semantics | Idempotent retry endpoint | A re-sent retry never double-queues a job |
| Persistence | H2 in-memory (required) | No durability across restarts; schema via JPA for a prototype |

## 4. Assumptions

- Single backend instance; the in-process worker is the only consumer.
- No authentication. That is a production gap, not a prototype feature.
- Payload is opaque text. The client checks length; it does not interpret the body.
- The UI does not duplicate `queueboard.processing.max-attempts`. A 409 is the retry rule.
- Job `type` is an open string. `echo` and `report` are input suggestions, not a closed list.
- The jobs board polls every 2 seconds. That interval is an injection token, not a scattered literal.

## 5. What I would do next with more time

1. **Push updates (SSE)** — replace polling with Server-Sent Events. The store is the only
   place that knows how fresh data arrives (ADR-0005), so the change stays local.
2. **A durable queue with competing consumers** — the in-process worker is single-instance
   by design. Production would claim jobs via `SELECT … FOR UPDATE SKIP LOCKED` (or a broker)
   so multiple instances can process safely; the optimistic-locking discipline already in
   place is the right foundation.
3. **Dead-letter view** — jobs that exhausted their attempts are visible as `FAILED` today,
   but a dedicated "needs attention" view (and an operator note field) would make triage easier.
4. **Pagination on the job list** — the board returns everything; fine for a prototype,
   not for thousands of jobs. The contract would gain `page`/`size` parameters first.
5. **An end-to-end test** — one Playwright happy-path (submit → watch it succeed → retry a
   flaky job) across the real stack; today the seam is covered from both sides instead
   (backend integration tests over HTTP, frontend store tests against a mocked API).
6. **AuthN/AuthZ** — Spring Security with per-user job visibility; left out deliberately,
   not accidentally (see assumptions).
