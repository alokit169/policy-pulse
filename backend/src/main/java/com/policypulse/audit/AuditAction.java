package com.policypulse.audit;

/** Actions worth recording. Later phases extend this. */
public enum AuditAction {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    SESSIONS_REVOKED,
    CUSTOMER_CREATED,
    CUSTOMER_UPDATED,
    CUSTOMER_ARCHIVED,
    CUSTOMER_RESTORED,
    POLICY_CREATED,
    POLICY_UPDATED,
    POLICY_STATUS_CHANGED,
    PREMIUM_RECORDED_PAID,
    PREMIUM_WAIVED,
    REMINDER_CONFIG_UPDATED
}
