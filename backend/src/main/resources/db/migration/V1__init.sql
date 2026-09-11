-- V1 schema: Policy Pulse

CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    email VARCHAR(255),
    phone VARCHAR(40),
    address VARCHAR(500),
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Kolkata',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE reminder_configurations (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL UNIQUE REFERENCES organizations(id),
    days_before_due VARCHAR(64) NOT NULL DEFAULT '10,5,1,0',
    days_after_due VARCHAR(64) NOT NULL DEFAULT '2',
    max_call_attempts INT NOT NULL DEFAULT 3,
    retry_delay_minutes INT NOT NULL DEFAULT 180,
    allowed_calling_start TIME NOT NULL DEFAULT '09:00',
    allowed_calling_end TIME NOT NULL DEFAULT '20:00',
    preferred_channel VARCHAR(32) NOT NULL DEFAULT 'IN_APP'
);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    manager_id UUID REFERENCES users(id),
    name VARCHAR(200) NOT NULL,
    email VARCHAR(255) NOT NULL,
    phone VARCHAR(40),
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    last_login_at TIMESTAMPTZ,
    UNIQUE (organization_id, email)
);

CREATE INDEX idx_users_org ON users(organization_id);
CREATE INDEX idx_users_manager ON users(manager_id);

CREATE TABLE customers (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    assigned_agent_id UUID NOT NULL REFERENCES users(id),
    customer_number VARCHAR(64) NOT NULL,
    first_name VARCHAR(120) NOT NULL,
    last_name VARCHAR(120) NOT NULL,
    phone VARCHAR(40) NOT NULL,
    alternate_phone VARCHAR(40),
    email VARCHAR(255),
    date_of_birth DATE,
    address VARCHAR(500),
    preferred_language VARCHAR(32) DEFAULT 'en',
    preferred_contact_time VARCHAR(64),
    communication_consent BOOLEAN NOT NULL DEFAULT TRUE,
    opted_out BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (organization_id, customer_number),
    UNIQUE (organization_id, phone)
);

CREATE INDEX idx_customers_org_agent ON customers(organization_id, assigned_agent_id);
CREATE INDEX idx_customers_name ON customers(organization_id, last_name, first_name);

CREATE TABLE policies (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    customer_id UUID NOT NULL REFERENCES customers(id),
    agent_id UUID NOT NULL REFERENCES users(id),
    policy_number VARCHAR(64) NOT NULL,
    insurance_provider VARCHAR(120) NOT NULL,
    policy_type VARCHAR(64) NOT NULL,
    plan_name VARCHAR(200),
    currency_code VARCHAR(8) NOT NULL DEFAULT 'INR',
    sum_assured NUMERIC(18,2),
    premium_amount NUMERIC(18,2) NOT NULL,
    premium_frequency VARCHAR(32) NOT NULL,
    policy_start_date DATE,
    policy_end_date DATE,
    maturity_date DATE,
    next_premium_due_date DATE,
    last_premium_paid_date DATE,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    nominee_name VARCHAR(200),
    bonus_amount NUMERIC(18,2),
    maturity_amount NUMERIC(18,2),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (organization_id, policy_number)
);

CREATE INDEX idx_policies_customer ON policies(customer_id);
CREATE INDEX idx_policies_due ON policies(organization_id, next_premium_due_date);
CREATE INDEX idx_policies_maturity ON policies(organization_id, maturity_date);

CREATE TABLE premium_payments (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    policy_id UUID NOT NULL REFERENCES policies(id),
    amount NUMERIC(18,2) NOT NULL,
    due_date DATE NOT NULL,
    paid_date DATE,
    status VARCHAR(32) NOT NULL,
    payment_reference VARCHAR(128),
    payment_method VARCHAR(64),
    verification_pending BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_premiums_policy ON premium_payments(policy_id);
CREATE INDEX idx_premiums_status_due ON premium_payments(organization_id, status, due_date);

CREATE TABLE reminders (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    customer_id UUID NOT NULL REFERENCES customers(id),
    policy_id UUID REFERENCES policies(id),
    reminder_type VARCHAR(32) NOT NULL,
    scheduled_at TIMESTAMPTZ NOT NULL,
    channel VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    idempotency_key VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (idempotency_key)
);

CREATE INDEX idx_reminders_due ON reminders(status, scheduled_at);

CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    customer_id UUID NOT NULL REFERENCES customers(id),
    policy_id UUID REFERENCES policies(id),
    agent_id UUID REFERENCES users(id),
    reminder_id UUID REFERENCES reminders(id),
    channel VARCHAR(32) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    summary TEXT,
    sentiment VARCHAR(32),
    outcome VARCHAR(64),
    duration_seconds INT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE conversation_messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender VARCHAR(32) NOT NULL,
    message TEXT NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,
    transcript_reference VARCHAR(255),
    metadata TEXT
);

CREATE TABLE follow_ups (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    customer_id UUID NOT NULL REFERENCES customers(id),
    policy_id UUID REFERENCES policies(id),
    conversation_id UUID REFERENCES conversations(id),
    assigned_agent_id UUID REFERENCES users(id),
    reason VARCHAR(64) NOT NULL,
    commitment_date DATE,
    due_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_followups_due ON follow_ups(organization_id, status, due_at);

CREATE TABLE human_tasks (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    customer_id UUID NOT NULL REFERENCES customers(id),
    policy_id UUID REFERENCES policies(id),
    conversation_id UUID REFERENCES conversations(id),
    assigned_agent_id UUID NOT NULL REFERENCES users(id),
    priority VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    reason VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    due_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE in_app_notifications (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    user_id UUID NOT NULL REFERENCES users(id),
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_notif_user ON in_app_notifications(user_id, read);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    organization_id UUID REFERENCES organizations(id),
    actor_id UUID,
    actor_email VARCHAR(255),
    action VARCHAR(80) NOT NULL,
    entity VARCHAR(80) NOT NULL,
    entity_id VARCHAR(80),
    timestamp TIMESTAMPTZ NOT NULL,
    metadata TEXT
);

CREATE INDEX idx_audit_org_time ON audit_logs(organization_id, timestamp);
