# Testing

How to check that every feature and every endpoint works: what is already
checked automatically, the two places nothing checks, and a walkthrough for the
parts a person has to look at.

| | |
| --- | --- |
| API endpoints | 46 |
| With automated cover | 44 |
| With none at all | 2 |
| Checks in total | 375 |

## Three commands answer most of the question

Run these before touching the UI. If all three pass, the logic, the wiring and
the container topology are all sound, and anything found by hand afterwards is a
gap in these rather than a surprise.

```bash
cd backend  && mvn test          # 256 tests, about two minutes
cd frontend && npm test          # 45 tests
docker compose up --build        # then the smoke script below, 74 checks
```

**`mvn test`** runs every rule against a real PostgreSQL through Testcontainers
and a clock frozen at a fixed instant. This is what proves tenant isolation, the
calling window, and that the assistant never records money as received. Because
it runs the real migrations under `ddl-auto=validate`, it also fails on schema
drift the way a production boot would.

**`npm test`** covers the browser side: the redirect after signing in, a session
expiring mid-request, money formatting, and that a double-click creates one thing
rather than two.

**The smoke test** drives the whole stack through nginx the way a browser does,
so it catches what unit tests structurally cannot — a missing proxy rule, a
migration that did not apply, a route nginx does not know about. It lives in
`ci.yml` rather than as a script; pull it out with:

```bash
python -c "import yaml,io; d=yaml.safe_load(io.open('.github/workflows/ci.yml',encoding='utf-8')); \
  step=[s for s in d['jobs']['compose']['steps'] if s.get('name','').startswith('Smoke test')][0]; \
  io.open('smoke.sh','w',newline='\n').write(step['run'])"
bash smoke.sh
```

> One run makes more than `RATE_LIMIT_RPM` requests. Run it twice inside a minute
> and the second is answered `429` most of the way down, which looks like twenty
> broken features and is the rate limiter working. Wait for the minute to roll
> over, or raise `RATE_LIMIT_RPM` for the local stack.

Everything in the script is repeatable against the same database. The one place
that changes seeded state — the password change — puts it back afterwards.

## Two endpoints nothing checks

| Endpoint | What it does |
| --- | --- |
| `PUT /api/policies/{id}/status` | Changing a policy to lapsed or surrendered |
| `POST /api/tasks/{id}/cancel` | Dropping a task. Its sibling `/complete` is covered; cancel is not |

Both are reachable in the UI, so steps 9 and 11 below exercise them. Everything
else on this page is belt and braces; these two are the only places where a
person looking is the only thing between a regression and production.

A third thing worth knowing: **recording a payment lost its smoke check** in
commit `c2de2e9`, when the follow-up block around it was rewritten. It is still
covered by nine places in the JUnit suite, so it is not a hole — but it no longer
runs against the real stack.

## What checks each endpoint

`yes` means that suite exercises it. Every one of the 46 is reachable from the
UI, so anything can also be checked by hand.

| | Endpoint | Smoke | JUnit |
| --- | --- | --- | --- |
| | **Signing in** | | |
| `POST` | `/api/auth/login` | yes | yes |
| `GET` | `/api/auth/me` | yes | yes |
| `POST` | `/api/auth/change-password` | yes | yes |
| `POST` | `/api/auth/logout-all` | – | yes |
| | **Customers** | | |
| `GET` | `/api/customers` | yes | yes |
| `POST` | `/api/customers` | yes | yes |
| `GET` | `/api/customers/{id}` | yes | yes |
| `PUT` | `/api/customers/{id}` | yes | yes |
| `DELETE` | `/api/customers/{id}` | yes | yes |
| `POST` | `/api/customers/{id}/restore` | – | yes |
| `GET` | `/api/customers/{id}/policies` | – | yes |
| `GET` | `/api/customers/{id}/conversations` | – | yes |
| `GET` | `/api/customers/{id}/follow-ups` | – | yes |
| | **Policies and premiums** | | |
| `GET` | `/api/policies` | yes | yes |
| `POST` | `/api/policies` | yes | yes |
| `GET` | `/api/policies/{id}` | yes | yes |
| `PUT` | `/api/policies/{id}` | yes | yes |
| `PUT` | `/api/policies/{id}/status` | **–** | **–** |
| `GET` | `/api/policies/{id}/premiums` | yes | yes |
| `POST` | `…/premiums/{premiumId}/pay` | – | yes |
| `POST` | `…/premiums/{premiumId}/waive` | – | yes |
| `POST` | `…/premiums/{premiumId}/dismiss-claim` | – | yes |
| | **Reminders and notifications** | | |
| `GET` | `/api/reminders` | yes | yes |
| `GET` | `/api/reminders/configuration` | yes | yes |
| `PUT` | `/api/reminders/configuration` | yes | yes |
| `POST` | `/api/reminders/detect` | yes | yes |
| `GET` | `/api/notifications` | yes | yes |
| `GET` | `/api/notifications/unread-count` | yes | yes |
| `POST` | `/api/notifications/{id}/read` | – | yes |
| `POST` | `/api/notifications/read-all` | – | yes |
| | **Conversations and the assistant** | | |
| `GET` | `/api/conversations` | yes | yes |
| `POST` | `/api/conversations` | yes | yes |
| `GET` | `/api/conversations/{id}` | yes | yes |
| `POST` | `/api/conversations/{id}/messages` | yes | yes |
| `POST` | `/api/conversations/{id}/analyse` | yes | yes |
| `POST` | `/api/conversations/{id}/close` | yes | yes |
| | **Follow-ups and tasks** | | |
| `GET` | `/api/follow-ups` | yes | yes |
| `POST` | `/api/follow-ups` | yes | yes |
| `GET` | `/api/follow-ups/{id}` | yes | yes |
| `POST` | `/api/follow-ups/run` | yes | – |
| `POST` | `/api/follow-ups/{id}/complete` | – | yes |
| `POST` | `/api/follow-ups/{id}/cancel` | – | yes |
| `GET` | `/api/tasks` | yes | yes |
| `POST` | `/api/tasks/{id}/complete` | – | yes |
| `POST` | `/api/tasks/{id}/cancel` | **–** | **–** |
| | **Dashboard** | | |
| `GET` | `/api/dashboard` | yes | yes |

## A pass through the whole application

Numbered because it is a sequence: each step leaves behind the data the next one
needs. Start from a fresh stack so the seeded figures below match:

```bash
docker compose down -v && docker compose up --build
```

Sign in at http://localhost:5173 as `admin@demo.local` / `Password123!`.

### 1. The dashboard tells the truth

Eight customers, eight policies, and an overdue total of **₹93,200 across 7
instalments**. Four names appear under work needing attention, on staggered dates
rather than all on one day.

- [ ] Figures are non-zero and the overdue dates differ from each other
- [ ] Scope reads as the whole organization, not one agent's book

### 2. Customers, and one who opted out

Search, filter by status, open a record. **Sunita Iyer** is the one who asked not
to be contacted — her record should say so, and step 5 will show reminders
skipping her.

- [ ] Search by name, phone and customer number each narrow the list
- [ ] Editing saves, and clearing an optional field empties it rather than storing a blank
- [ ] Archive a customer, confirm they leave the active list, then restore them

### 3. A policy, and the schedule it generates

Create one with a start and end date and a monthly premium. The schedule should
appear immediately and **run the full term** — a ten-year monthly policy has 121
instalments, not a handful.

- [ ] The schedule covers the whole term, first instalment on the start date
- [ ] Past instalments show as overdue, future ones as upcoming
- [ ] A negative premium is refused with a message, not a stack trace

### 4. Money in — *no smoke cover*

Record a payment against an overdue instalment. The amount is **never taken from
you** — it is whatever the instalment says — so recording one cannot quietly
change what was owed. Then waive a different one.

- [ ] Paying marks it paid, sets the date, and the dashboard's overdue total drops by that amount
- [ ] Waiving settles it without recording money
- [ ] Paying the same instalment twice is refused the second time

### 5. Reminders find what is due

Press **Run detection now** on Reminders. Three reminders are raised — Anita,
Kabir and Priya each have an instalment due today. Press it again: nothing new,
because a reminder's key is built from what it is about, not when it was made.

- [ ] First run raises 3; the second raises 0
- [ ] Sunita Iyer is not among them
- [ ] Changing the calling window and channel saves, and the timezone shown is the tenant's

### 6. A reminder goes out

Set the channel to **VOICE** with a window covering now, then detect. The call is
placed by the mock, a conversation appears with a transcript, and the reminder is
completed. Set the window to a closed hour instead and nothing is dialled.

- [ ] Inside the window: a conversation with a transcript, reminder completed
- [ ] Outside it: nothing sent, reminder still pending, and it says when it will try again
- [ ] On EMAIL the hour is ignored — an email goes out where a text would have waited

### 7. The assistant reads a call

Open the seeded conversation with Priya Menon and press analyse. It should find a
payment commitment and raise a follow-up. Then log a conversation where the
customer says *"I have already paid"* and analyse that: **the instalment must not
be marked paid** — it is flagged and a person is asked to check.

- [ ] A commitment becomes a follow-up dated for the day the customer named
- [ ] A claimed payment leaves the instalment unpaid and raises a task
- [ ] Only the customer's lines count — an agent saying "so you'll pay Friday?" is not a commitment

### 8. The follow-up engine

Press **Work through them now** on Follow-ups. On a fresh stack the seeded
promise — made for a day already gone, still unpaid — comes due and is escalated,
and a `BROKEN_PAYMENT_COMMITMENT` task appears. Run it again: nothing.

- [ ] First run brings 1 due and escalates 1; the second does nothing
- [ ] Record the payment behind a due commitment, run again, and it closes itself
- [ ] Completing and cancelling a follow-up by hand both work

### 9. Tasks — *cancel has no cover*

Two open tasks on a fresh stack: the broken promise from step 8 and a
`VERIFY_CLAIMED_PAYMENT`. Complete one. **Cancel the other** — this is one of the
two endpoints nothing automated touches.

- [ ] Complete closes a task and it leaves the open list
- [ ] Cancel closes one too, and cancelling an already-closed task is refused

### 10. Notifications

Steps 5 and 8 leave unread notifications for the agent who holds those customers.
Sign in as `agent@demo.local` to see them.

- [ ] The header count matches the unread list, and reading one decrements it
- [ ] Mark all read empties it

### 11. Policy status — *no cover at all*

Set a policy to lapsed. The other of the two uncovered endpoints — and it has a
consequence worth confirming: **a policy that is not active is no longer chased**,
so its reminders stop.

- [ ] The status changes and shows on the policy list
- [ ] Detection no longer raises reminders for it

### 12. Your own password

Settings, reachable from your name in the header. Changing it **signs out every
device including this one**, so expect to land back at the sign-in page.

- [ ] A wrong current password is refused; a short new one is refused
- [ ] After changing it, the old password fails and the new one works
- [ ] Change it back, so the demo account is where the docs say it is

## Things that must refuse

A feature that works is easy to check. What actually protects an agency is what
the application declines to do, and none of it is visible unless you go looking.

### A. An agent sees only their own book

Sign in as `agent@demo.local` and then as `agent2@demo.local`. The customer lists
differ, and neither matches the admin's.

- [ ] Each agent's dashboard says it is scoped to their own book
- [ ] Opening a peer's customer by pasting the URL answers `404`, not `403` — 403 would confirm the record exists

### B. An agent cannot run the engines

Signed in as an agent, **Run detection now** and **Work through them now** are not
on the page at all. Calling them anyway is refused.

- [ ] Neither button is visible to an agent
- [ ] `POST /api/reminders/detect` with an agent's token answers `403`

### C. Signing in gives nothing away

A wrong password, an unknown address and a disabled account all answer the same
`401` with the same words. Ten wrong guesses lock the account for fifteen minutes
— and a locked account still answers exactly the same thing.

- [ ] Wrong password and unknown address are indistinguishable
- [ ] After ten failures the correct password is refused too, and says nothing about being locked

### D. It refuses to start unsafely

```bash
docker compose run --rm -e APP_DEV_MODE=false backend
```

It should **refuse to boot** and name both reasons: the signing key published in
this repository, and demo seeding being on.

- [ ] The container exits with both problems named, not just the first

### E. No provider sends anything

Nothing in the demo can reach a real person: every seeded address ends
`@example.test`. Selecting a real provider is refused rather than accepted and
ignored.

- [ ] `VOICE_PROVIDER=twilio` or `NOTIFICATION_PROVIDER=real` stops the backend at startup

## Exploring the API directly

Swagger UI at http://localhost:8080/swagger-ui.html lists all 46 endpoints with
their request and response shapes. To call an authenticated one, get a token
first:

```bash
TOKEN=$(curl -s -X POST http://localhost:5173/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@demo.local","password":"Password123!"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

curl -s -H "Authorization: Bearer $TOKEN" http://localhost:5173/api/dashboard
```

Use port **5173** rather than 8080, so the request goes through nginx exactly as
the browser's would. Port 8080 has no pages of its own, so opening it in a
browser answers `401` — the correct answer to an unauthenticated API request, and
not a fault.

## Where the tests live

| Suite | Covers |
| --- | --- |
| `backend/src/test/java/…/auth` | Sign-in, token revocation, lockout, password change |
| `…/customers` | CRUD and the isolation rules between agents and tenants |
| `…/policies`, `…/premiums` | Schedules, payments, concurrent payment recording |
| `…/reminders` | Detection, dispatch, the calling window, two sweeps racing |
| `…/voice`, `…/messaging` | Calls and messages, retries, unusable addresses |
| `…/ai` | What the assistant may and may not do |
| `…/followups` | The engine's calendar, and two runs racing |
| `…/config` | The startup refusals and the demo data's own claims |
| `frontend/src/lib`, `…/pages` | The API client, the auth context, redirects, forms |

`LOCAL_DEVELOPMENT.md` has more on running each suite and on the frozen clock the
backend tests use.
