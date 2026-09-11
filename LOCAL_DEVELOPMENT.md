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
| `ErrorHandlingTest` | Unmatched routes return 404, protected routes 403, health and API docs public |
| `RateLimitFilterTest` | Per-client counting and window eviction |

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
