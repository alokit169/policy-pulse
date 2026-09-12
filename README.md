# Policy Pulse

Customer and policy management for a small insurance agency, with the chasing
built in: premiums that fall due raise reminders, reminders go out as calls,
emails or texts, what a customer says on a call is read back into a commitment,
and a commitment nobody keeps ends up in front of a person.

A working application rather than a demo of one — twelve build phases, with a
review after most of them that went looking for defects rather than for
agreement. The last four carry a commit of their own for what the review found.
Why the code is shaped the way it is, is written down beside the code that
shaped it.

> **Nothing is actually sent to anybody.** The telephony, email, SMS and language
> models are all mocks. Selecting a real provider is refused at startup rather
> than accepted and silently ignored, because reminders marked as sent that
> nobody received is the failure that would be noticed last. See
> [what is deliberately unfinished](#what-is-deliberately-unfinished).

Not affiliated with any insurer. Provider names are data fields.

## Running it

```bash
docker compose up --build
```

| | |
| --- | --- |
| **The app** | **http://localhost:5173** |
| API | http://localhost:8080 |
| API reference | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |

Sign in at **5173**. Port 8080 is the backend on its own: it has no pages, so
opening it directly answers `401 Authentication required`, which is the correct
answer to an unauthenticated API request and not a fault.

Demo accounts, all on `Password123!`:

| Email | Role | Sees |
| --- | --- | --- |
| `admin@demo.local` | Organization admin | The whole agency |
| `agent@demo.local` | Agent | Their own customers only |
| `agent2@demo.local` | Agent | Theirs — so the split is visible |

More detail, including running it outside Docker: [LOCAL_DEVELOPMENT.md](LOCAL_DEVELOPMENT.md).

## What is already in there

The demo agency is seeded on first boot with a book chosen for coverage rather
than volume, and every date is worked out from today, so it is never stale:

- Eight customers — two up to date, two with this month outstanding, four a month
  behind on staggered dates, and one of those four opted out of contact
- A call that was answered, with its transcript, and the promise made on it
- That promise, for a day already gone: **run the follow-up engine and watch it
  reach a person**
- Somebody claiming to have paid, flagged for checking and never recorded as
  received

Sign in as `admin@demo.local`, press *Run detection now* on Reminders and then
*Work through them now* on Follow-ups, and most of the system has shown itself.
Both are manager-only, so an agent does not see them.

## What it does

| | |
| --- | --- |
| **Customers and policies** | Per-agency records, premium schedules generated from the policy term, payments and waivers |
| **Reminders** | Premiums falling due raise reminders, in the tenant's own timezone, on the channel the tenant prefers |
| **Calls** | A reminder can be placed as a call, inside the tenant's calling window, with a transcript kept |
| **Email and SMS** | The same, in writing. A text keeps to the calling window because it arrives with a noise; an email does not |
| **The assistant** | Reads a transcript and says what it thinks was meant. Everything it produces passes a validation layer before anything is written |
| **Follow-ups** | Commitments, and an engine that closes the ones already paid, announces the ones due, and escalates the ones broken |
| **Dashboard** | What is overdue, what is coming, what needs a person — scoped to an agent's own book or the whole agency |

Some of the rules are more interesting than the features:

- **No endpoint accepts an organization id from the client.** It always comes
  from the caller's token.
- **Denials answer 404, not 403.** Answering 403 would confirm the record exists.
- **The assistant never records money as received.** A customer saying they have
  paid is a claim: the instalment is flagged and a person checks it.
- **A promise already kept closes itself.** Sending an agent to ring somebody who
  has already paid costs the agency the relationship, not just the call.
- **Development is what you opt into.** A deployment that sets nothing refuses to
  start on the signing key published in this repository.

## How it is built

Spring Boot 3.3 on Java 21, PostgreSQL 16 with Flyway, React 18 with TypeScript
and Vite, nginx in front. 125 backend source files, 38 API endpoints, 12
migrations.

```
backend/src/main/java/com/policypulse/
  organizations  users  auth  audit          the agency and who works there
  customers  policies  premiums              the book of business
  reminders  notifications                   noticing what needs chasing
  voice  messaging                           doing the chasing
  conversations  ai  followups  tasks        what came of it
  dashboard                                  what it all adds up to
  common  config  security                   the plumbing
```

[ARCHITECTURE.md](ARCHITECTURE.md) has the module table and the flow through
them.

## Tests

```bash
cd backend  && mvn test     # 256 tests
cd frontend && npm test     # 45 tests
```

The backend suite runs against a real PostgreSQL through Testcontainers, so the
migrations and `ddl-auto=validate` are exercised exactly as a real boot would,
and against a frozen clock so anything depending on "today" is deterministic.

CI runs both, builds the frontend, then stands the whole Docker stack up and puts
74 checks through nginx the way a browser would.

One habit is worth naming, because it found things reading never did: **a green
test is not evidence until the red one has been seen.** Five separate bugs in
this codebase were the same shape — a decision read a moment before it was
written — and not one was visible in the code. They were found by running the
operation from a thread pool, or clicking the button twice, and counting.

## What is deliberately unfinished

Written down rather than left to be discovered:

| | |
| --- | --- |
| No real providers | Voice, email, SMS and the language model are mocks. Real ones need webhooks, signature verification, bounce handling and unsubscribe |
| Accepted is not delivered | A provider taking a message is recorded as sent; there are no delivery receipts |
| No deployment | It runs on a laptop. No TLS, secrets management, managed database, backups or metrics |
| No password reset | A password can be changed by somebody who knows it; forgetting it still needs an administrator |
| No MFA, no refresh tokens | Single factor, and a session lasts exactly one token lifetime |

[SECURITY.md](SECURITY.md) keeps the full list, with what each one costs.

## Docs

| | |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Modules, the flow between them, and the phase roadmap |
| [LOCAL_DEVELOPMENT.md](LOCAL_DEVELOPMENT.md) | Running it, VS Code, the test suites, watching each part work |
| [DATABASE.md](DATABASE.md) | Schema, migrations and how tenancy is enforced |
| [SECURITY.md](SECURITY.md) | The auth model, isolation, what the assistant and the calling path may do, and every known gap |

`DEPLOYMENT.md` does not exist yet, because nothing has been deployed.
