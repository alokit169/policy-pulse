package com.policypulse.reminders;

import com.policypulse.common.Domain;
import com.policypulse.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/reminders")
@Tag(name = "Reminders")
public class ReminderController {
    private final ReminderService reminderService;

    public ReminderController(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    @GetMapping
    @Operation(summary = "Reminders for the caller's organization, newest first")
    public PageResponse<ReminderResponse> search(
            @RequestParam(required = false) Domain.ReminderStatus status,
            @PageableDefault(size = 20, sort = "scheduledAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return reminderService.search(status, pageable);
    }

    @GetMapping("/configuration")
    @Operation(summary = "The organization's reminder settings")
    public ReminderConfigurationResponse getConfiguration() {
        return reminderService.getConfiguration();
    }

    @PutMapping("/configuration")
    @Operation(summary = "Change the organization's reminder settings")
    public ReminderConfigurationResponse updateConfiguration(
            @Valid @RequestBody ReminderConfigurationRequest request) {
        return reminderService.updateConfiguration(request);
    }

    @PostMapping("/detect")
    @Operation(summary = "Raise and deliver the caller's organization's due reminders now")
    public Map<String, Integer> detectNow() {
        ReminderService.DetectionRun run = reminderService.detectNow();
        return Map.of("created", run.created(), "skipped", run.skipped(), "sent", run.sent());
    }
}
