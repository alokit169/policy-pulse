# Local development

## Prerequisites

| Tool | Version |
| --- | --- |
| JDK | 21 |
| Maven | 3.9+ |
| Node | 18+ |
| Docker | with Compose v2 |

## Option A — everything in Docker

```bash
docker compose up --build
```

| Service | URL |
| --- | --- |
| UI | http://localhost:5173 |
| API | http://localhost:8080 |
| Swagger | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |

Postgres is exposed on `localhost:5432` (`policypulse` / `policypulse` / db `policypulse`).

Tear down, keeping data:

```bash
docker compose down
```

Tear down and drop the database volume:

```bash
docker compose down -v
```

## Option B — Postgres in Docker, app on the host

Gives you hot reload on both sides.

```bash
# 1. database only
docker compose up -d postgres

# 2. backend (port 8080)
cd backend
mvn spring-boot:run

# 3. frontend (port 5173, proxies /api and /actuator to 8080)
cd frontend
npm install
npm run dev
```

## VS Code

Open the repository root as the workspace folder (not `backend/` or `frontend/`),
then accept the recommended extensions when prompted. The ones that matter are
the Java and Spring Boot packs, Tailwind IntelliSense and Docker.

The repository ships shared `launch.json` and `tasks.json`. Run tasks with
**Ctrl+Shift+P -> Tasks: Run Task**.

| Task | Does |
| --- | --- |
| `Stack: up (everything in Docker)` | Builds and starts all three services |
| `Stack: down` | Stops them, keeping the database |
| `Stack: logs` | Tails all container logs |
| `Postgres: start` | Database only, for host-side development |
| `Backend: run` | `mvn spring-boot:run` with seeding on |
| `Backend: test` | The test suite (default test task) |
| `Frontend: dev` | Vite dev server (default build task is `Frontend: build`) |

### Debugging

**F5 -> `Backend: debug`** launches the app with breakpoints, hot-swapping
method bodies on save. It needs Postgres running and port 8080 free, so stop the
backend container first:

```bash
docker compose stop backend frontend
docker compose up -d postgres
```

`Full stack: debug` starts Postgres and the Vite server, then launches the
backend debugger and a Chrome window against http://localhost:5173.

Breakpoints in the browser work through the `Frontend: debug in Chrome`
configuration; `.tsx` files map back to source automatically.

### Which mode to use

Use Docker when you want the real deployed topology, including nginx. Use the
host-side mode while writing code: Vite reloads the browser on save and the
Spring Boot devtools-free restart is still faster than rebuilding an image. Note
that in host-side mode nginx is not in the path, so a route added to
`vite.config.ts` must also be added to `nginx.conf` before it works in Docker.

## Configuration

Copy `.env.example` to `.env` and adjust as needed. Defaults are wired for local
development, so the app runs without any `.env` present.

| Variable | Default | Purpose |
| --- | --- | --- |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/policypulse` | JDBC URL |
| `DATABASE_USERNAME` | `policypulse` | DB user |
| `DATABASE_PASSWORD` | `policypulse` | DB password |
| `JWT_SECRET` | dev placeholder | HMAC key, min 32 chars |
| `CORS_ORIGINS` | `http://localhost:5173,http://localhost:80` | Allowed origins |
| `RATE_LIMIT_RPM` | `120` | Requests per minute per client |
| `APP_SEED` | `false` | Seed the demo agency on first boot |
| `APP_DEV_MODE` | `false` | Turns the startup safety checks into warnings. Needed to run with the placeholder key or with seeding on |
| `AI_PROVIDER` | `mock` | AI backend |
| `VOICE_PROVIDER` | `mock` | Voice backend. Only `mock` is implemented; `twilio` fails at startup on purpose |
| `NOTIFICATION_PROVIDER` | `mock` | Email and SMS backend. Only `mock` is implemented; `real` fails at startup on purpose |

Never commit a real `JWT_SECRET`. `.env` is gitignored.

`APP_DEV_MODE` is the one to understand. The application refuses to start on the
shipped signing key, a wildcard CORS origin, or demo seeding, because a
deployment that sets nothing should get the safe behaviour rather than a silent
unsafe one. Saying `APP_DEV_MODE=true` turns each into a warning; docker compose
sets it, and `.env.example` has it. Anywhere else, set a real `JWT_SECRET`
instead.

## The demo agency

`APP_SEED=true` creates *Demo Insurance Agency*, an Indian tenant so the demo
exercises a timezone whose day rolls over before the server's. Three accounts,
all on `Password123!`:

| Account | Role | Sees |
| --- | --- | --- |
| `admin@demo.local` | Organization admin | The whole agency |
| `agent@demo.local` | Agent | Their own customers only |
| `agent2@demo.local` | Agent | Theirs, which is how the split is visible |

Eight customers, chosen for coverage rather than volume: two up to date, two with
this month outstanding, four a month behind on staggered dates, and one of those
four opted out — so detection can be seen skipping somebody. Every address is
`@example.test`, which cannot be delivered to.

There is also a call that was answered, the promise made on it, and a claimed
payment. The promise is for a day that has passed, so the first run of the
follow-up engine escalates it; the claimed payment sits flagged for a person,
never recorded as received. The two are on different customers on purpose: on one
person they cancel out, correctly, and neither would be visible.

Seeding is idempotent — it stops the moment it finds the admin account — so
restarting never duplicates anything. To start over: `docker compose down -v`.

## Database schema

Flyway owns the schema; migrations live in
`backend/src/main/resources/db/migration`. JPA runs with `ddl-auto: validate`,
so an entity that drifts from the migrations fails startup rather than silently
altering tables. Add a new `V<n>__<name>.sql` for every schema change — never
edit an applied migration.

## Tests

```bash
cd backend
mvn test
```

The suite starts a real PostgreSQL via Testcontainers, applies the Flyway
migrations and runs Hibernate with `ddl-auto: validate`. An entity that drifts
from the migrations fails the build, which is the same check a production boot
performs. Docker must be running.

| Test | Guards |
| --- | --- |
| `SchemaIntegrityTest` | Entities match the migrations; every migration applied |
| `ErrorHandlingTest` | Unmatched routes return 404, anonymous requests 401, health and API docs public |
| `AuthIntegrationTest` | Login, token issue, enumeration resistance, live revocation, audit |
| `CustomerIsolationTest` | Tenant and role isolation: no cross-tenant or cross-agent access |
| `CustomerCrudTest` | Create, update, archive, restore, search, paging, conflicts |
| `PolicyIsolationTest` | Policies stay in their tenant, including the customer they reference |
| `PolicyPremiumTest` | Schedule generation, payment, waiving, money scale |
| `PremiumScheduleTest` | Due-date arithmetic, including month-end and leap years |
| `PremiumConcurrencyTest` | Simultaneous payments settle an instalment exactly once |
| `ReminderDetectionTest` | Detection is repeatable, honours consent and reads each tenant's own date |
| `ReminderDispatchTest` | Reminders are delivered at the scheduled moment, and only once |
| `ReminderDispatchConcurrencyTest` | Two sweeps racing for one reminder place one call, not two |
| `ReminderOffsetsTest` | Parsing the configured day offsets |
| `ReminderApiTest` | Settings permissions, validation, notification ownership |
| `FixedClockTest` | The suite really is running against a frozen clock |
| `DashboardTest` | Aggregates, agent and tenant scoping, rupee totals |
| `ConversationTest` | Transcripts, closing, tenant and agent visibility |
| `FollowUpTest` | Commitments, ownership, timezone-aware due moments |
| `FollowUpEngineTest` | Promises kept closing themselves, promises broken reaching a person |
| `FollowUpConcurrencyTest` | Two sweeps racing for one follow-up tell the agent once |
| `AiSafetyTest` | What the assistant may cause, and above all what it may not |
| `MockAIProviderTest` | Intent rules, including that only the customer is read |
| `VoiceCallTest` | The calling window, attempt limits, retries and the transcript a call leaves |
| `MessagingTest` | Which channel may be used when, unusable addresses, retries, and what is recorded |
| `MockMessageProviderTest` | The mock outcomes are the same every time, and addresses stay out of logs |
| `MessageContentTest` | A message names the instalment it is about, and nothing typed can reshape it |
| `LoginLockoutTest` | An account stops answering after enough wrong guesses, and does not say so |
| `LockoutConcurrencyTest` | Guesses arriving together are all counted, and a lock that runs out gives the attempts back |
| `PasswordChangeTest` | Changing a password needs the old one and ends every session |
| `StartupChecksTest` | An unsafe configuration refuses to start unless the machine says it is a development one |
| `DemoBookTest` | The demo data says what it claims: who is behind, who opted out, what is owed |
| `RateLimitFilterTest` | Per-client counting and window eviction |
| `FilterRegistrationTest` | Security filters are not also auto-registered in the servlet chain |

### Time in tests

The suite runs against a clock frozen at a fixed instant, so anything that
depends on "today" is deterministic. `FixedClockTest` exists because the
override silently did not apply at first, and every date-sensitive test was
quietly running against the real date instead.

### Status codes

`401` means no credentials were supplied or they were rejected, so the client
should sign in. `403` means the caller is authenticated but not permitted. Spring
returns `403` for anonymous requests by default; `SecurityConfig` overrides that
so a SPA can tell the two apart.

CI runs the same suite plus the frontend build and a smoke test of the full
Docker stack. See `.github/workflows/ci.yml`.

### Watching a message go out

Nothing is actually sent. As a manager, set `preferredChannel` to `EMAIL` or
`SMS` on the Reminders page, give a customer a policy with a premium due today,
and run detection. What went out appears in that customer's conversation history
alongside any calls.

The mocks decide their outcome from the address, so a demo can show each case: an
address at `invalid.test` bounces, one at `fail.test` errors and is retried, and a
phone number ending in `2` or `3` does the same. Everything else is accepted.

A text is only sent inside the tenant's calling window — an email ignores it —
so `"sent":0` on an SMS run usually means the window, not a failure.

### Watching a promise be kept or broken

The engine runs hourly, and `POST /api/follow-ups/run` does one tenant now. As a
manager, the Follow-ups page has the same button.

Record a commitment against a policy with an unpaid premium, then run it: the
follow-up comes due and the customer's agent is told. Record the payment and run
it again — the follow-up closes itself, because nobody should be sent to ring
somebody who has already paid.

A promise is only broken once the day it named has passed, so that half cannot be
produced by clicking: the API will not accept a commitment for a day already gone.
`FollowUpEngineTest` covers it against a frozen clock.

### Watching a call happen

The mock provider dials nobody, but the rest of the path is real. Its outcome is
decided by the last digit of the number, so a call can be steered: `0` rings out,
`1` is busy, `2` cannot be dialled, and anything else is answered.

Sign in as `admin@demo.local`, then, against `/api/reminders/configuration`, set
`preferredChannel` to `VOICE` and widen `allowedCallingStart`/`allowedCallingEnd`
to cover the time you are actually working — the window is read in the *tenant's*
timezone, not the server's, so a UK evening is the middle of an Indian night and
nothing will be dialled. Give a customer a policy with a premium due today, then
`POST /api/reminders/detect`.

A delivered call answers `{"created":1,"skipped":0,"sent":1}` and leaves a
conversation with a transcript. `POST /api/conversations/{id}/analyse` then reads
it the way the assistant would.

If the response says `"sent":0`, the window is the first thing to check: the
reminder stays `PENDING` and will go out when the window opens, which is the
guard working rather than a failure.

## Common commands

```bash
# backend
cd backend
mvn package              # build jar (target/policy-pulse-0.1.0.jar)
mvn test                 # tests (H2, Flyway disabled)
mvn spring-boot:run      # run against local Postgres

# frontend
cd frontend
npm run dev              # dev server
npm run build            # typecheck + production build
npm run typecheck        # types only
```

## Troubleshooting

**Backend exits at startup with a schema validation error.** An entity no longer
matches the migrations. Compare the entity to `V1__init.sql` and add a migration.

**`docker compose up` cannot bind 8080 or 5432.** Something is already on the
port — often a host-run backend or a local Postgres. Stop it, or change the
published port in `docker-compose.yml`.

**Frontend builds but API calls 404 in dev.** The Vite proxy only forwards
`/api`, `/v3` and `/actuator`. Add new prefixes to `server.proxy` in
`vite.config.ts` and to `nginx.conf` for the Docker path.

**Maven cannot replace the jar on Windows.** A running app holds a lock on
`target/*.jar`. Stop it before rebuilding.

**Tests fail with "Could not find a valid Docker environment".** Docker is not
running, or the engine rejects the API version the Docker client library sends.
Docker Engine 29 refuses anything below API 1.44 and answers `/info` with a bare
400, which Testcontainers reports as no Docker environment. The build pins the
version via the `docker.api.version` property in `backend/pom.xml`; on an engine
older than Docker 25 override it:

```bash
mvn test -Ddocker.api.version=1.41
```

Check what your engine accepts with:

```bash
docker version --format 'API {{.Server.APIVersion}}, min {{.Server.MinAPIVersion}}'
```
