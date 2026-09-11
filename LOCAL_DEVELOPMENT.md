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
| `APP_SEED` | `false` | Seed demo data on boot |
| `AI_PROVIDER` | `mock` | AI backend |
| `VOICE_PROVIDER` | `mock` | Voice backend |
| `NOTIFICATION_PROVIDER` | `mock` | Notification backend |

Never commit a real `JWT_SECRET`. `.env` is gitignored.

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
| `RateLimitFilterTest` | Per-client counting and window eviction |

### Status codes

`401` means no credentials were supplied or they were rejected, so the client
should sign in. `403` means the caller is authenticated but not permitted. Spring
returns `403` for anonymous requests by default; `SecurityConfig` overrides that
so a SPA can tell the two apart.

CI runs the same suite plus the frontend build and a smoke test of the full
Docker stack. See `.github/workflows/ci.yml`.

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
