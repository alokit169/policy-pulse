package com.policypulse.notifications;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String title,
        String body,
        boolean read,
        Instant createdAt) {

    public static NotificationResponse of(InAppNotification n) {
        return new NotificationResponse(n.getId(), n.getTitle(), n.getBody(), n.isRead(), n.getCreatedAt());
    }
}
