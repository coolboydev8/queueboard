# AI-Use Note

## Where AI helped

- Filled the existing Angular scaffold (shell, jobs feature, HTTP error interceptor) against
  `docs/api/openapi.yaml` and the ADRs, instead of inventing a second structure.
- Drafted the signal store, the small presentational components, and the Vitest cases for
  polling, unknown status, stale refresh, and retry.
- Implemented the backend skeleton (entity + state machine, service rules, controller,
  problem-details handler, simulated worker) against the per-class specs I had written as
  Javadoc TODOs, and drafted the six backend test classes from the same specs.

## Prompts / techniques that worked

- Read the assessment and the ADRs before generating code, and kept the result inside those
  boundaries: contract-first types, layered feature folder, backend as the only writer of
  job state.
- Asked for a plan that named files and rejected scope (no in-browser processor) before
  implementation, then checked the generated store against the installed Angular 21
  `resource()` types rather than the model's memory of the API.
- For the backend, wrote the specification first — the OpenAPI contract plus a Javadoc TODO
  on every class stating its exact responsibilities — and had AI implement to those specs.
  That kept generation honest: the retry decision table, transition matrix, and error shapes
  were all decided (and reviewable) before any code existed.
- Had AI verify version facts against the installed dependencies instead of its memory:
  Spring Boot 4 ships Jackson 3 (`tools.jackson.*`) and moved `@WebMvcTest`/`@MockitoBean`
  (see weaknesses below) — both were confirmed by inspecting the resolved jars.

## What I independently verified

- `mvn verify` (backend): the full pyramid — state-machine matrix, service rules with Mockito,
  `@WebMvcTest` HTTP contract (status codes, Location header, problem+json shapes), processor
  unit tests, `@SpringBootTest` integration tests against real H2 with the worker polling
  (Awaitility, no fixed sleeps), ArchUnit layering rules, and the JaCoCo ≥ 80% gate.
- The full stack manually: backend + frontend running together — a submitted job visibly moves
  PENDING → PROCESSING → SUCCEEDED on the board; a `fail` payload fails with its error shown;
  a `flaky` payload fails once and recovers after Retry; a retry after the third failed attempt
  is refused with a 409 the UI renders as a banner.
- `npm test` (Vitest, 13 tests): status badge, problem+json parsing, the create form, and the store
  (poll start/stop, unknown status, stale refresh, retry response, retry refusal, missing job).
- `npm run build` production bundle, under the size budget.
- In the browser at `http://127.0.0.1:4200`: the jobs page shows an error banner when `/api/jobs`
  is not the QueueBoard API (nothing is presented as an empty successful board); choosing Failed
  sets `?status=FAILED`; a blank submit shows "Enter a name." and "Enter a type."; a filled
  submit stays on the form and shows the error; `/does-not-exist` is the not-found page; a job
  URL the server answers with 404 shows "Job not found", not a success state. Phone width wraps
  the filter and keeps the actions as buttons, not full-width bars.
- Confirmed `resource()` drops its value on error (Angular 21.2 source) and that unknown
  statuses take the dashed badge, not the success badge. The status-0 "backend unreachable"
  path is covered by the interceptor unit test; this machine's port 8080 answered with a 404,
  so the browser showed that 404 instead.

## A suggestion I rejected or modified

**Backend — modified the claim design.** The first proposal had the worker find a PENDING
job and then start it in two separate service calls. That opens a window where the job is
claimed in between, surfacing the lost race as a second exception type
(`InvalidJobTransitionException`) the worker would also have to treat as "skip". I changed
it to a single-transaction `claimNextPending()` (find + transition + flush), so every lost
race surfaces as exactly one `OptimisticLockingFailureException` — one rule, one catch.

**Frontend — rejected scope.** An in-browser job simulator, and experimental signal forms,
were both set aside. A simulator would let the screen move a job to "succeeded" with no
server response, which breaks the consistency rule. The UI talks only to `/api`. Signal
forms are still experimental in Angular 21, so the create form is a typed reactive
`FormGroup` with the contract's length limits. `resource()` is also marked experimental,
but it stays inside `JobsStore`; pages depend on the store's signals, not on that API.

## Known weaknesses found in AI output

- Generated backend tests initially imported `@WebMvcTest` from its Spring Boot 3 package
  (`org.springframework.boot.test.autoconfigure.web.servlet`) and Jackson from
  `com.fasterxml.jackson.databind`. Boot 4 modularized test slices into
  `spring-boot-starter-webmvc-test` (package `org.springframework.boot.webmvc.test.autoconfigure`)
  and switched to Jackson 3 (`tools.jackson.databind`). The compiler caught both; the lesson
  is that model memory lags a major framework release.
- A first version of the optimistic-locking test compared versions through the detached
  entity passed to `save(...)` — JPA's merge returns the updated instance and leaves the
  argument stale, so the assertion was wrong even though the locking behavior was right.
- Mockito argument-matcher misuse (`fail(job.getId(), contains(...))` mixes a raw value
  with a matcher) — the test run caught it immediately.
- `/h2-console` 404'd at first: Boot 4 also modularized the H2 console out of the web
  starter. Caught by actually requesting the URL, fixed by adding `spring-boot-h2console`.
- The first form template used `formControlName` without `[formGroup]`. The component test
  caught it (`NG01050`) before the page was exercised.
- `resource()` does not keep the previous list when a reload fails (`value()` throws and
  `hasValue()` is false). A note that "the resource keeps the last value" would have shown
  stale data as a normal success. The store keeps its own snapshot and a stale flag.
- Angular 21.2's test builder peers on Vitest 4. The scaffold's Vitest 3 range does not
  install. The frontend dependency was moved to Vitest 4 so `npm ci` resolves.
