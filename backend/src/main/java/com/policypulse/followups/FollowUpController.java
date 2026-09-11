package com.policypulse.followups;

import com.policypulse.common.Domain;
import com.policypulse.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/follow-ups")
@Tag(name = "Follow-ups")
public class FollowUpController {
    private final FollowUpService followUpService;

    public FollowUpController(FollowUpService followUpService) {
        this.followUpService = followUpService;
    }

    @GetMapping
    @Operation(summary = "Follow-ups the caller owns, soonest first")
    public PageResponse<FollowUpResponse> search(
            @RequestParam(required = false) Domain.FollowUpStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return followUpService.search(status, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One follow-up")
    public FollowUpResponse get(@PathVariable UUID id) {
        return followUpService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record something that needs coming back to")
    public FollowUpResponse create(@Valid @RequestBody FollowUpRequest request) {
        return followUpService.create(request);
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "Mark a follow-up done")
    public FollowUpResponse complete(@PathVariable UUID id,
                                     @RequestBody(required = false) Map<String, String> body) {
        return followUpService.complete(id, body == null ? null : body.get("notes"));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Drop a follow-up that is no longer needed")
    public FollowUpResponse cancel(@PathVariable UUID id,
                                   @RequestBody(required = false) Map<String, String> body) {
        return followUpService.cancel(id, body == null ? null : body.get("notes"));
    }
}
