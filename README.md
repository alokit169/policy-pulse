# Policy Pulse

AI-powered insurance agent management and customer engagement platform. Production-oriented web app for insurance agents and agencies: customers, policies, premiums, reminders, AI-assisted follow-ups, and (mocked) voice engagement.

Not affiliated with LIC. Provider names are data fields.

## Quick start

See [LOCAL_DEVELOPMENT.md](LOCAL_DEVELOPMENT.md).

```bash
docker compose up --build
```

- UI: http://localhost:5173
- API: http://localhost:8080
- Swagger: http://localhost:8080/swagger-ui.html

**Demo login** (seeded when `APP_SEED=true`, which docker compose sets):

| Account | Email | Password | Role |
| --- | --- | --- | --- |
| Agent | `agent@demo.local` | `Password123!` | AGENT |
| Admin | `admin@demo.local` | `Password123!` | ORGANIZATION_ADMIN |

Sign in at http://localhost:5173. The API issues a bearer token from
`POST /api/auth/login`; `GET /api/auth/me` returns the token holder.

## Docs

- [ARCHITECTURE.md](ARCHITECTURE.md) — modules, flows and the phase roadmap
- [LOCAL_DEVELOPMENT.md](LOCAL_DEVELOPMENT.md) — running it, VS Code, tests
- [DATABASE.md](DATABASE.md) — schema, migrations and multi-tenancy
- [SECURITY.md](SECURITY.md) — auth model, isolation rules, what the assistant and the calling path may do, and known gaps

The live API reference is Swagger UI at
http://localhost:8080/swagger-ui.html once the stack is running.

What the assistant and the calling path are allowed to do turned out to belong
with the rest of the limits, so it lives in SECURITY.md rather than in
documents of its own. `DEPLOYMENT.md` is still to come.
