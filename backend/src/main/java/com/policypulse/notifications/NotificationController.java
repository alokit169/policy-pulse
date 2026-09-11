package com.policypulse.notifications;

import com.policypulse.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Every route acts on the caller's own notifications only. */
@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications")
public class NotificationController {
    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @Operation(summary = "The caller's notifications, newest first")
    public PageResponse<NotificationResponse> list(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @PageableDefault(size = 20) Pageable pageable) {
        return notificationService.listMine(unreadOnly, pageable);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "How many of the caller's notifications are unread")
    public Map<String, Long> unreadCount() {
        return Map.of("unread", notificationService.unreadCount());
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark one notification read")
    public NotificationResponse markRead(@PathVariable UUID id) {
        return notificationService.markRead(id);
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark every unread notification read")
    public Map<String, Integer> markAllRead() {
        return Map.of("updated", notificationService.markAllRead());
    }
}
