package com.policypulse.audit;

/** Actions worth recording. Later phases extend this. */
public enum AuditAction {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    CUSTOMER_CREATED,
    CUSTOMER_UPDATED,
    CUSTOMER_ARCHIVED,
    CUSTOMER_RESTORED
}
