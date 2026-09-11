# Security

How authentication, authorisation and tenant isolation work, and what is
deliberately not solved yet.

## Authentication

Login exchanges an email and password for a signed JWT. Passwords are stored as
bcrypt hashes and never leave the server.

| Property | Value |
| --- | --- |
| Algorithm | HMAC-SHA256 |
| Lifetime | 8 hours (`JWT_EXPIRATION_MS`) |
| Claims | subject (user id), `email`, `role`, `ver` (token version) |
| Transport | `Authorization: Bearer <token>` |

There is no refresh token, so the lifetime is the whole session length.

### Login does not reveal which accounts exist

Every failure returns the same `401` and the same message, whether the email is
unknown, the password is wrong or the account is disabled. An unknown email is
still compared against a dummy bcrypt hash so that a missing account costs the
same time as a wrong password and cannot be told apart by timing. The real
reason is written to the audit log instead.

The status check runs *after* the password check, so probing whether an account
is disabled still requires knowing its password.

## Revoking access

A JWT cannot be withdrawn once issued, so two checks run on **every** request,
not just at login:

1. **Account status.** A user who is no longer `ACTIVE` is rejected immediately,
   rather than staying signed in until their token expires.
2. **Token version.** Each user has a `token_version`; tokens carry the value
   they were minted with. Raising the version invalidates every token already
   issued to that user, on every device.

`POST /api/auth/logout-all` raises the version. A password-change endpoint should
raise it too when it is added.

Plain sign-out is client-side only: it discards the stored token, which stays
technically valid until it expires. Use sign-out-everywhere when a token may be
compromised.

> Both checks cost a database read per request, which is why the filter loads the
> user anyway.

## Authorisation

Roles are `SUPER_ADMIN`, `ORGANIZATION_ADMIN`, `MANAGER` and `AGENT`.

Every tenant-scoped table carries `organization_id`, and **no endpoint accepts an
organization id from the client** — it always comes from the caller's token. An
agent sees only the records assigned to them; a manager or admin sees the whole
organization.

### Denials are 404, not 403

A record in another tenant, and a record belonging to a peer agent, both answer
`404`. Answering `403` would confirm that the record exists, which is itself a
leak. `403` is reserved for a caller who is authenticated and may know the record
exists but is not allowed to act on it.

`401` means no credentials were supplied or they were rejected. Spring returns
`403` for anonymous requests by default; `SecurityConfig` overrides that so a
client can tell "sign in" from "you may not do this".

## Rate limiting

A fixed window of `RATE_LIMIT_RPM` requests per minute per client IP, rejected
with `429`. Behind nginx the real client comes from `X-Forwarded-For`, honoured
via `server.forward-headers-strategy`.

> **The backend must not be publicly reachable in production.** Forwarded headers
> are trusted, so a client that can reach the backend directly can spoof its
> address and sidestep the limit. Only the proxy should be exposed.

## Response headers

`Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff` and
`X-Frame-Options: DENY`. CORS origins come from `CORS_ORIGINS`; credentials are
allowed, so it must never be set to `*`.

## Secrets

`JWT_SECRET` must be at least 32 characters and must be set explicitly outside
development — the default in `application.yml` is a placeholder and is not
secret. `.env` is gitignored.

Rotating `JWT_SECRET` invalidates every existing token, which is the blunt
instrument if the signing key itself is ever exposed.

## Demo data

`APP_SEED=true` creates a demo organization with two accounts on a well-known
password. docker compose sets it for local runs. **Never enable it outside
development.**

## What the assistant is allowed to do

A model reads transcripts and says what it thinks was meant. It is a source of
opinion, not of authority: everything it produces passes through the validation
layer before anything is written.

**It never records money as received.** A customer saying they have paid is a
claim. The instalment is flagged for verification and a person is asked to check
it against the books; the status is untouched. A person then either records the
payment properly or dismisses the claim.

**Below a confidence threshold it does nothing but ask for help**, and changing a
customer's own record needs a higher bar again.

**What it may do unattended only ever reduces what the system does** — stopping
contact after an opt-out, or scheduling a callback. Those are safe in the
direction they fail: the cost of being wrong is a missed reminder, not a wrong
balance.

It is never given identifiers or contact details, only what is needed to read the
call, so a logged prompt cannot leak them. It is never asked which record it is
talking about; the system already knows, from the conversation the transcript came
from.

Only what the customer said is read. An agent asking "so you will pay on Friday?"
is not a commitment, or the system could talk itself into one.

## What the system may do on the phone

Calls are placed by a scheduler, with nobody signed in, so nothing on that path
reads a security context: every record written carries the tenant taken from the
reminder rather than from a caller.

Three things are checked before a number is dialled, and the telephony provider
is trusted with none of them.

**The calling window** is checked against the clock at the moment of dialling, in
the tenant's own timezone — not when the reminder was queued. A reminder due at
nine in the morning can be picked up hours late by a sweep that fell behind, and
ringing a customer at eleven at night is the kind of mistake that ends an agency.
A tenant sets its own window and a reminder outside it is left alone, not
dropped.

**The attempt limit** is the tenant's, and the count rises on every attempt
whatever the outcome, so a provider that fails every call still runs out rather
than dialling for ever. Past the limit the reminder is given up on and a
`HumanTask` is raised for the customer's own agent, so unreachable work becomes
somebody's instead of disappearing.

**A number that cannot be dialled is not retried**, because trying again cannot
help. It goes straight to a person.

**A reminder is claimed before it is dialled**, with a conditional update that
only one caller can win. The hourly sweep and a manager running detection by hand
can want the same reminder at the same moment; checking its status in memory and
then writing it lets both through, and by the time the second one notices, the
customer has been rung twice.

Consent is checked earlier still, at detection: an opted-out customer leaves no
queued reminder at all, so there is nothing for a later run to act on by mistake.

Phone numbers are masked in logs.

> `app.voice.provider=twilio` selects a provider that is not implemented and
> fails loudly. Silently placing no calls would be the failure noticed last:
> reminders would keep being marked as attempted and nobody would ever be rung.

## Known gaps

These are understood and deferred, not overlooked.

| Gap | Consequence |
| --- | --- |
| No refresh tokens | A session lasts exactly one token lifetime, then requires signing in again |
| No password reset or change | Credentials can only be set by seeding or directly in the database |
| No per-account lockout | Rate limiting is per IP, so it does not stop a slow distributed guessing attack against one account |
| Audit log is append-only but unreviewed | Nothing surfaces `LOGIN_FAILURE` patterns yet |
| No MFA | Single factor only |
| No real telephony provider | Calls are simulated; a live provider needs webhook signature checks and replay safety before it is wired in |

## Reporting

This is a private project under active development. Raise an issue on the
repository for anything found here.
