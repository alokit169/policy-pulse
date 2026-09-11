package com.policypulse.tasks;

import com.policypulse.common.Domain;
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

import java.util.UUID;

/** Work the assistant handed over, and anything else needing a person. */
@RestController
@RequestMapping("/api/tasks")
@Tag(name = "Tasks")
public class HumanTaskController {
    private final HumanTaskService taskService;

    public HumanTaskController(HumanTaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    @Operation(summary = "Tasks the caller owns, newest first")
    public PageResponse<HumanTaskResponse> search(
            @RequestParam(required = false) Domain.HumanTaskStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return taskService.search(status, pageable);
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "Mark a task done")
    public HumanTaskResponse complete(@PathVariable UUID id) {
        return taskService.complete(id);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Drop a task that is not needed")
    public HumanTaskResponse cancel(@PathVariable UUID id) {
        return taskService.cancel(id);
    }
}
