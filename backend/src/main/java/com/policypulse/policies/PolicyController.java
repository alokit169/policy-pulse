package com.policypulse.policies;

import com.policypulse.common.Domain;
import com.policypulse.common.PageResponse;
import com.policypulse.premiums.PremiumResponse;
import com.policypulse.premiums.PremiumService;
import com.policypulse.premiums.RecordPaymentRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Scoped to the caller's organization by the service; no route accepts an
 * organization id from the client.
 */
@RestController
@RequestMapping("/api/policies")
@Tag(name = "Policies")
public class PolicyController {
    private final PolicyService policyService;
    private final PremiumService premiumService;

    public PolicyController(PolicyService policyService, PremiumService premiumService) {
        this.policyService = policyService;
        this.premiumService = premiumService;
    }

    @GetMapping
    @Operation(summary = "List policies in the caller's organization")
    public PageResponse<PolicyResponse> search(
            @RequestParam(required = false) Domain.PolicyStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return policyService.search(status, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one policy")
    public PolicyResponse get(@PathVariable UUID id) {
        return policyService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a policy and generate its premium schedule")
    public PolicyResponse create(@Valid @RequestBody PolicyRequest request) {
        return policyService.create(request);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a policy and rebuild its unsettled premiums")
    public PolicyResponse update(@PathVariable UUID id, @Valid @RequestBody PolicyRequest request) {
        return policyService.update(id, request);
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "Change a policy's status")
    public PolicyResponse changeStatus(@PathVariable UUID id, @RequestParam Domain.PolicyStatus status) {
        return policyService.changeStatus(id, status);
    }

    @GetMapping("/{id}/premiums")
    @Operation(summary = "The policy's premium schedule, oldest first")
    public List<PremiumResponse> premiums(@PathVariable UUID id) {
        return premiumService.listForPolicy(id);
    }

    @PostMapping("/{id}/premiums/{premiumId}/pay")
    @Operation(summary = "Record that a premium was collected")
    public PremiumResponse recordPayment(@PathVariable UUID id, @PathVariable UUID premiumId,
                                         @Valid @RequestBody(required = false) RecordPaymentRequest request) {
        return premiumService.recordPayment(id, premiumId,
                request == null ? new RecordPaymentRequest(null, null, null) : request);
    }

    @PostMapping("/{id}/premiums/{premiumId}/dismiss-claim")
    @Operation(summary = "Dismiss a claimed payment without recording one")
    public PremiumResponse dismissClaim(@PathVariable UUID id, @PathVariable UUID premiumId) {
        return premiumService.dismissVerification(id, premiumId);
    }

    @PostMapping("/{id}/premiums/{premiumId}/waive")
    @Operation(summary = "Waive a premium that will not be collected")
    public PremiumResponse waive(@PathVariable UUID id, @PathVariable UUID premiumId) {
        return premiumService.waive(id, premiumId);
    }
}
