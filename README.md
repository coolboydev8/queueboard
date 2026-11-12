# QueueBoard

A small application for submitting and monitoring background jobs. Users can submit jobs,
watch them move through a defined lifecycle (`PENDING → PROCESSING → SUCCEEDED | FAILED`),
inspect details and errors, and retry failed jobs within defined rules.

Processing is **simulated** — no real external workloads are executed.

## Repository layout

```
queueboard/
├── backend/          # Spring Boot 4 (Java 21) REST API + simulated job processor + H2
├── frontend/         # Angular 21 single-page app (standalone components, signals)
├── docs/
│   ├── api/          # OpenAPI contract — the agreed interface between backend & frontend
│   ├── adr/          # Architecture Decision Records (why, not just what)
│   ├── DESIGN.md     # Short design note: architecture, assumptions, trade-offs
│   └── AI_USAGE.md   # AI-use note: where AI helped, what was verified, what was rejected
└── .github/workflows # CI: build + tests + coverage gate on every push/PR
```

## Prerequisites

| Tool    | Version                              | Check           |
| ------- | ------------------------------------ | --------------- |
| JDK     | 21 (LTS)                             | `java -version` |
| Node.js | 22 LTS (20.19+ and 24 are supported) | `node -v`       |
| npm     | 10+ (ships with Node 22)             | `npm -v`        |
| Maven   | not required                         | the included wrapper downloads Maven 3.9 on first run |

The project runs on macOS, Linux and Windows. Commands are the same on every system
unless a Windows variant is shown.

The Maven wrapper uses `JAVA_HOME` when it is set, otherwise the `java` on your `PATH`.
If more than one JDK is installed, point the current terminal at JDK 21 first:

```bash
# macOS
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"

# Linux — the directory name depends on your distribution and vendor
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

```powershell
# Windows (PowerShell) — use the folder of your JDK 21 install
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21"
```

## Quick start

Use two terminals, both starting from the repository root.

### 1. Backend (http://localhost:8080)

macOS / Linux:

```bash
cd backend
./mvnw spring-boot:run
```

Windows (PowerShell or Command Prompt):

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

The backend is ready when the log prints `Started QueueBoardApplication`. To confirm:

```bash
curl http://localhost:8080/actuator/health      # Windows PowerShell: curl.exe ...
# {"groups":["liveness","readiness"],"status":"UP"}
```

- H2 is in-memory; state resets on restart (by design — see `docs/DESIGN.md`).
- H2 console (dev only): http://localhost:8080/h2-console
- OpenAPI UI: http://localhost:8080/swagger-ui.html

### 2. Frontend (http://localhost:4200)

Same on every system:

```bash
cd frontend
npm ci           # first run only
npm start        # dev server with /api proxied to the backend (proxy.conf.json)
```

Open http://localhost:4200. Stop either process with `Ctrl+C`.

## Running tests

Backend — unit, web-slice and integration tests, with the JaCoCo coverage gate
(report: `backend/target/site/jacoco/index.html`).

macOS / Linux:

```bash
cd backend
./mvnw verify
```

Windows (PowerShell or Command Prompt):

```powershell
cd backend
.\mvnw.cmd verify
```

Frontend — component and unit tests (Vitest), then the production build. Same on every
system:

```bash
cd frontend
npm test
npm run build
```

## Troubleshooting

**`./mvnw: Permission denied` (macOS / Linux).** The wrapper script lost its executable
flag, which can happen when the source is copied without file permissions:

```bash
chmod +x mvnw
```

**`release version 21 not supported` or `UnsupportedClassVersionError`.** The terminal is
using an older JDK. Select JDK 21 as shown under Prerequisites, then run the command again.

**`Port 8080 was already in use`.** Find what is holding the port:

```bash
lsof -i :8080                    # macOS / Linux
netstat -ano | findstr :8080     # Windows
```

Either stop that program, or run the backend on another port:

```bash
SERVER_PORT=8081 ./mvnw spring-boot:run                    # macOS / Linux
```

```powershell
$env:SERVER_PORT = "8081"; .\mvnw.cmd spring-boot:run      # Windows (PowerShell)
```

Then set `target` in `frontend/proxy.conf.json` to the same port and restart `npm start`.

## API contract

The REST contract lives in [`docs/api/openapi.yaml`](docs/api/openapi.yaml) and is written
**before** implementation — frontend and backend both build against it. Errors use
RFC 9457 Problem Details.

## Design & decisions

- [`docs/DESIGN.md`](docs/DESIGN.md) — architecture overview, assumptions, trade-offs
- [`docs/adr/`](docs/adr/) — individual decision records
- [`docs/AI_USAGE.md`](docs/AI_USAGE.md) — AI usage disclosure

## Demo: forcing failures and retries

Processing is simulated (2–6 s per job). Two payload markers make failure paths
demonstrable on demand (configurable under `queueboard.processing.*`):

| Payload contains | Behavior |
| --- | --- |
| `fail`  | Every attempt fails — after 3 attempts, retry is refused with HTTP 409. |
| `flaky` | Only the **first** attempt fails — press **Retry** and watch it recover. |
| anything else | Succeeds. |

## Known limitations

- **Single-instance processor.** The worker claims jobs in-process; there is no distributed
  locking, so running two backend instances would double-process. A durable queue with
  competing consumers (`SKIP LOCKED` or a broker) is the production path — see
  `docs/DESIGN.md` §5.
- **H2 in-memory.** All state resets on restart (per the assignment; `ddl-auto: create-drop`).
- **Polling, not push.** The UI refreshes every 2 s while jobs are active; status changes can
  be up to one interval stale (trade-off in ADR-0005).
- **No authentication or multi-tenancy.** Every user sees every job.
- **No pagination.** The list returns all jobs, newest first — fine at prototype scale.
- **Retried jobs jump the queue.** Claiming is oldest-`createdAt`-first and `createdAt`
  never changes, so a retried job is picked up before newer `PENDING` jobs instead of
  re-queuing at the back. Accepted as a harmless prototype quirk.
