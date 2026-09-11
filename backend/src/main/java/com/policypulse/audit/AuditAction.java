package com.policypulse.audit;

/** Actions worth recording. Later phases extend this. */
public enum AuditAction {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    SESSIONS_REVOKED,
    CUSTOMER_CREATED,
    CUSTOMER_UPDATED,
    CUSTOMER_ARCHIVED,
    CUSTOMER_RESTORED
}
