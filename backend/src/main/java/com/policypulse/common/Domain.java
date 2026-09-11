package com.policypulse.common;

public final class Domain {
    private Domain() {}

    public enum Role { SUPER_ADMIN, ORGANIZATION_ADMIN, MANAGER, AGENT }
    public enum EntityStatus { ACTIVE, INACTIVE, SUSPENDED }
    public enum PolicyStatus { ACTIVE, LAPSED, MATURED, SURRENDERED, PAID_UP, CANCELLED }
    public enum PremiumStatus { UPCOMING, DUE, OVERDUE, PAID, FAILED, WAIVED, PENDING_VERIFICATION }
    public enum PremiumFrequency { MONTHLY, QUARTERLY, HALF_YEARLY, YEARLY }
    public enum ReminderType {
        PREMIUM_DUE, PREMIUM_OVERDUE, POLICY_MATURITY, BONUS_AVAILABLE,
        DOCUMENT_REQUIRED, CUSTOM, FOLLOW_UP
    }
    public enum ReminderStatus { PENDING, SCHEDULED, IN_PROGRESS, SENT, FAILED, CANCELLED, COMPLETED }
    public enum Channel { IN_APP, EMAIL, SMS, WHATSAPP, VOICE }
    public enum ConversationStatus { STARTED, IN_PROGRESS, COMPLETED, FAILED, ESCALATED }
    public enum ConversationDirection { OUTBOUND, INBOUND }
    public enum FollowUpStatus { OPEN, DUE, COMPLETED, CANCELLED }
    public enum HumanTaskStatus { OPEN, IN_PROGRESS, COMPLETED, CANCELLED }
    public enum HumanTaskPriority { LOW, MEDIUM, HIGH, URGENT }
    public enum AiIntent {
        PAYMENT_CONFIRMED, PAYMENT_COMMITMENT, PAYMENT_DELAYED, CANNOT_PAY,
        CALL_LATER, WRONG_NUMBER, NO_LONGER_INTERESTED, REQUEST_HUMAN_AGENT,
        POLICY_QUERY, MATURITY_QUERY, BONUS_QUERY, DOCUMENT_REQUEST,
        GENERAL_QUERY, UNKNOWN, OPT_OUT
    }
}
