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

## Known gaps

These are understood and deferred, not overlooked.

| Gap | Consequence |
| --- | --- |
| No refresh tokens | A session lasts exactly one token lifetime, then requires signing in again |
| No password reset or change | Credentials can only be set by seeding or directly in the database |
| No per-account lockout | Rate limiting is per IP, so it does not stop a slow distributed guessing attack against one account |
| Audit log is append-only but unreviewed | Nothing surfaces `LOGIN_FAILURE` patterns yet |
| No MFA | Single factor only |

## Reporting

This is a private project under active development. Raise an issue on the
repository for anything found here.
