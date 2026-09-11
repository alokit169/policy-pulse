package com.policypulse.notifications;

import com.policypulse.common.ApiException;
import com.policypulse.common.PageResponse;
import com.policypulse.security.AuthUser;
import com.policypulse.security.SecurityUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class NotificationService {
    private final InAppNotificationRepository notifications;

    public NotificationService(InAppNotificationRepository notifications) {
        this.notifications = notifications;
    }

    /**
     * Called by the reminder detector rather than by a request, so the recipient
     * is passed in explicitly instead of taken from a security context.
     */
    @Transactional
    public void notifyUser(UUID organizationId, UUID userId, String title, String body) {
        notifications.save(new InAppNotification(organizationId, userId, title, body));
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> listMine(boolean unreadOnly, Pageable pageable) {
        UUID userId = SecurityUtil.current().getId();

        Page<InAppNotification> page = unreadOnly
                ? notifications.findByUserIdAndReadFalseOrderByCreatedAtDesc(userId, pageable)
                : notifications.findByUserIdOrderByCreatedAtDesc(userId, pageable);

        return new PageResponse<>(
                page.getContent().stream().map(NotificationResponse::of).toList(),
                page.getTotalElements(), page.getNumber(), page.getSize());
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return notifications.countByUserIdAndReadFalse(SecurityUtil.current().getId());
    }

    @Transactional
    public NotificationResponse markRead(UUID id) {
        AuthUser caller = SecurityUtil.current();

        // Scoped by recipient: a notification belonging to someone else is
        // reported as missing rather than forbidden.
        InAppNotification notification = notifications.findByIdAndUserId(id, caller.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Notification not found"));

        notification.markRead();
        notifications.save(notification);
        return NotificationResponse.of(notification);
    }

    @Transactional
    public int markAllRead() {
        return notifications.markAllReadFor(SecurityUtil.current().getId());
    }
}
