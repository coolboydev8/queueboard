# ADR-0002: Package by feature, with controller → service → repository layering inside

- Status: Accepted
- Date: 2026-11-09

## Context

The standard backend style here is a layered architecture: thin controllers, business
rules in services, Spring Data JPA for persistence. A pure *package-by-layer* layout
(`controllers/`, `services/`, `repositories/`) scatters one feature across the tree;
a prototype with essentially one aggregate (`Job`) benefits from cohesion.

## Decision

Package by feature (`com.queueboard.job`) with the conventional layers as sub-packages:

```
job/
├── api/           # controllers + DTOs + mapping (the HTTP edge; validation lives here)
├── domain/        # Job, JobStatus state machine, JobService, domain exceptions
├── persistence/   # Spring Data repositories
└── processing/    # simulated async worker that drives the lifecycle
```

Cross-cutting concerns stay out of the feature: `common/web` (error handling),
`config/` (async + properties). An ArchUnit test enforces the dependency direction
(api → domain ← persistence/processing; nothing depends on api).

## Consequences

- Adding a feature = adding a package, not touching four layer folders.
- The layering the team expects is still there and machine-enforced, not just convention.
