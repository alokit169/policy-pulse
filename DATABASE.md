# Database

PostgreSQL 16. Flyway owns the schema; JPA never alters it.

## Migrations

Migrations live in `backend/src/main/resources/db/migration` and are named
`V<n>__<description>.sql`.

| Version | Adds |
| --- | --- |
| `V1__init` | All thirteen tables, constraints and indexes |
| `V2__auth_and_audit` | Globally unique user email, audit log indexes |
| `V3__token_revocation` | `users.token_version` |
| `V4__premium_schedule` | Unique instalment per policy and due date |
| `V5__premium_optimistic_locking` | `premium_payments.version` |
| `V6__reminder_queries` | Indexes for reminder listing, detection and unread counts |

### Rules

**Never edit a migration that has been applied.** Flyway records a checksum for
each one; changing it fails validation on the next start. Add a new version
instead.

**Every schema change needs a migration.** JPA runs with `ddl-auto: validate`, so
an entity that drifts from the schema fails startup rather than silently
altering tables. `SchemaIntegrityTest` runs the same check against a real
Postgres in CI, so drift fails the build rather than a deployment.

## Multi-tenancy

Every tenant-scoped table carries `organization_id`. There is no row-level
security policy in the database: scoping is enforced in the service layer, which
takes the organization from the caller's token and never from the request. Every
repository method used by those services is scoped by it — for example
`findByIdAndOrganizationId` rather than `findById`.

`CustomerIsolationTest` covers this directly, because a mistake here exposes one
agency's book to another.

## Tables

### Identity and tenancy

| Table | Notes |
| --- | --- |
| `organizations` | The tenant |
| `users` | Belongs to one organization. `manager_id` self-references for reporting lines |
| `reminder_configurations` | One per organization |

`users.email` is unique **globally**, not per organization: login takes an email
and no tenant hint, so a duplicate would make the account lookup ambiguous. One
email therefore identifies exactly one user.

### Customer domain

| Table | Notes |
| --- | --- |
| `customers` | Unique `(organization_id, customer_number)` and `(organization_id, phone)` |
| `policies` | References a customer. Unique `(organization_id, policy_number)` |
| `premium_payments` | Instalments, unique per `(policy_id, due_date)` |

`premium_payments` carries a version column. Recording a payment reads an
instalment, checks it is not already settled and then writes, so two simultaneous
requests could otherwise both pass the check and both write, leaving one payment
reference overwritten and two audit entries for a single collection. The version
makes the losing write fail so the caller is told to retry. Policies are
deliberately not versioned: their cached premium dates are derived from the
instalments and recomputed on every change, so a lost update there corrects
itself, and versioning them would reject two agents settling different
instalments of the same policy at the same moment.

A policy's schedule is generated from its start date, end date and frequency.
Regenerating it after the terms change leaves paid and waived instalments
untouched and drops only unsettled ones, so a premium that was actually
collected survives a correction to the policy.

Customers are archived (`status = INACTIVE`) rather than deleted, because
policies and premium history reference them.

### Engagement

| Table | Notes |
| --- | --- |
| `reminders` | Detected work, scheduled per configuration. Unique `idempotency_key` |
| `conversations`, `conversation_messages` | Calls and transcripts |
| `follow_ups`, `human_tasks` | Commitments and work requiring a person |
| `in_app_notifications` | Delivered in the UI |

Reminder detection runs on a timer, so it will be run again over the same data.
Each reminder carries an `idempotency_key` built from what it is about, not when
it was made: the reminder type, the policy, the instalment's due date and the
configured offset. The column is unique, so a repeated run, an overlapping run
or a retry after a crash all converge on the same set of reminders rather than
raising duplicates.

Dates are read in each organization's own timezone, taken from
`organizations.timezone`. Reading them in UTC would chase the wrong day's
premiums for part of every day for any tenant not on UTC.

### Audit

`audit_logs` is append-only: rows are never updated or deleted, and the entity
exposes no setters. `organization_id` and `actor_id` are nullable because a
failed login may not identify either.

Audit writes run in their own transaction, so a failed request still records the
attempt rather than rolling the record back with it.

## Reading money across records

Currency belongs to the policy, so one tenant can hold both rupee and dollar
policies. Any total that spans policies is therefore grouped by currency and the
figures are never added together; only the instalment count is combined. The
dashboard queries do this in SQL.

Whether a premium is overdue is decided by its due date, not by its stored
status. A status is written when the schedule is generated and is not rewritten
as days pass, so an untouched row can still read UPCOMING long after it was due.

## Conventions

- Primary keys are `UUID`, generated by the application in `@PrePersist`.
- Timestamps are `TIMESTAMPTZ` and Hibernate is pinned to UTC.
- Enumerations are stored as `VARCHAR` via `@Enumerated(EnumType.STRING)`, so
  reordering a Java enum cannot corrupt stored data.
- Money uses `NUMERIC`, never floating point.

## Connecting

```bash
docker compose up -d postgres
docker compose exec postgres psql -U policypulse -d policypulse
```

Credentials and database name all default to `policypulse` locally; see
`.env.example`.
