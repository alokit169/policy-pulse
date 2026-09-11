package com.policypulse.premiums;

import com.policypulse.audit.AuditAction;
import com.policypulse.audit.AuditService;
import com.policypulse.common.ApiException;
import com.policypulse.common.Domain;
import com.policypulse.policies.Policy;
import com.policypulse.policies.PolicyRepository;
import com.policypulse.security.AuthUser;
import com.policypulse.security.SecurityUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PremiumService {
    private static final String ENTITY = "PremiumPayment";

    /** Instalments that are settled: never rewritten or removed by regeneration. */
    private static final Set<Domain.PremiumStatus> SETTLED =
            EnumSet.of(Domain.PremiumStatus.PAID, Domain.PremiumStatus.WAIVED);

    private final PremiumPaymentRepository premiums;
    private final PolicyRepository policies;
    private final AuditService audit;
    private final Clock clock;

    public PremiumService(PremiumPaymentRepository premiums, PolicyRepository policies,
                          AuditService audit, Clock clock) {
        this.premiums = premiums;
        this.policies = policies;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Brings a policy's schedule in line with its current terms.
     *
     * <p>Settled instalments are left alone: a paid premium is a record of
     * something that happened, and shortening the term afterwards must not erase
     * it. Unsettled instalments that fall outside the new term are dropped, so
     * correcting an end date does not leave orphaned dues behind.
     */
    @Transactional
    public void regenerateSchedule(Policy policy) {
        LocalDate end = policy.getPolicyEndDate() != null ? policy.getPolicyEndDate() : policy.getMaturityDate();
        List<LocalDate> wanted = PremiumSchedule.dueDates(policy.getPolicyStartDate(), end, policy.getPremiumFrequency());

        List<PremiumPayment> existing = premiums.findByPolicyIdOrderByDueDateAsc(policy.getId());
        Map<LocalDate, PremiumPayment> byDueDate = existing.stream()
                .collect(Collectors.toMap(PremiumPayment::getDueDate, Function.identity(), (a, b) -> a));

        LocalDate today = LocalDate.now(clock);
        List<PremiumPayment> toSave = new ArrayList<>();

        for (LocalDate due : wanted) {
            PremiumPayment instalment = byDueDate.get(due);
            if (instalment == null) {
                toSave.add(newInstalment(policy, due, today));
            } else if (!SETTLED.contains(instalment.getStatus())) {
                // The premium itself may have been corrected.
                instalment.setAmount(policy.getPremiumAmount());
                instalment.setStatus(PremiumSchedule.statusOn(due, today));
                toSave.add(instalment);
            }
        }

        Set<LocalDate> wantedDates = Set.copyOf(wanted);
        List<PremiumPayment> orphaned = existing.stream()
                .filter(p -> !wantedDates.contains(p.getDueDate()))
                .filter(p -> !SETTLED.contains(p.getStatus()))
                .toList();

        premiums.saveAll(toSave);
        premiums.deleteAll(orphaned);
        premiums.flush();

        refreshPolicyDates(policy);
    }

    /**
     * Recomputes the policy's cached premium dates from its instalments, so the
     * two can never disagree.
     */
    @Transactional
    public void refreshPolicyDates(Policy policy) {
        List<PremiumPayment> instalments = premiums.findByPolicyIdOrderByDueDateAsc(policy.getId());

        policy.setNextPremiumDueDate(instalments.stream()
                .filter(p -> !SETTLED.contains(p.getStatus()))
                .map(PremiumPayment::getDueDate)
                .min(Comparator.naturalOrder())
                .orElse(null));

        policy.setLastPremiumPaidDate(instalments.stream()
                .filter(p -> p.getStatus() == Domain.PremiumStatus.PAID)
                .map(PremiumPayment::getPaidDate)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null));

        policies.save(policy);
    }

    @Transactional(readOnly = true)
    public List<PremiumResponse> listForPolicy(UUID policyId) {
        requireVisiblePolicy(policyId);
        return premiums.findByPolicyIdOrderByDueDateAsc(policyId).stream()
                .map(PremiumResponse::of)
                .toList();
    }

    @Transactional
    public PremiumResponse recordPayment(UUID policyId, UUID premiumId, RecordPaymentRequest request) {
        AuthUser caller = SecurityUtil.current();
        Policy policy = requireVisiblePolicy(policyId);

        PremiumPayment instalment = premiums.findByIdAndOrganizationId(premiumId, caller.getOrganizationId())
                .filter(p -> p.getPolicyId().equals(policyId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Premium not found"));

        if (instalment.getStatus() == Domain.PremiumStatus.PAID) {
            throw new ApiException(HttpStatus.CONFLICT, "This premium is already recorded as paid");
        }

        LocalDate today = LocalDate.now(clock);
        LocalDate paidDate = request.paidDate() != null ? request.paidDate() : today;
        if (paidDate.isAfter(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Paid date cannot be in the future");
        }

        instalment.setStatus(Domain.PremiumStatus.PAID);
        instalment.setPaidDate(paidDate);
        instalment.setPaymentReference(blankToNull(request.paymentReference()));
        instalment.setPaymentMethod(blankToNull(request.paymentMethod()));
        instalment.setVerificationPending(false);
        premiums.save(instalment);

        refreshPolicyDates(policy);

        audit.record(AuditAction.PREMIUM_RECORDED_PAID, ENTITY, instalment.getId().toString(),
                caller.getOrganizationId(), caller.getId(), caller.getUsername(),
                "policy=" + policyId + " amount=" + instalment.getAmount());
        return PremiumResponse.of(instalment);
    }

    /**
     * Clears a claim the assistant flagged, without paying anything.
     *
     * <p>The other half of the rule that the assistant may never record money as
     * received. A person checks the books and either records the payment properly,
     * which marks it paid, or dismisses the claim here, which leaves the instalment
     * exactly as it was.
     */
    @Transactional
    public PremiumResponse dismissVerification(UUID policyId, UUID premiumId) {
        AuthUser caller = SecurityUtil.current();
        Policy policy = requireVisiblePolicy(policyId);

        PremiumPayment instalment = premiums.findByIdAndOrganizationId(premiumId, caller.getOrganizationId())
                .filter(p -> p.getPolicyId().equals(policyId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Premium not found"));

        instalment.setVerificationPending(false);
        premiums.save(instalment);
        refreshPolicyDates(policy);

        audit.record(AuditAction.PREMIUM_VERIFICATION_RESOLVED, ENTITY, instalment.getId().toString(),
                caller.getOrganizationId(), caller.getId(), caller.getUsername(), "dismissed");
        return PremiumResponse.of(instalment);
    }

    @Transactional
    public PremiumResponse waive(UUID policyId, UUID premiumId) {
        AuthUser caller = SecurityUtil.current();
        Policy policy = requireVisiblePolicy(policyId);

        PremiumPayment instalment = premiums.findByIdAndOrganizationId(premiumId, caller.getOrganizationId())
                .filter(p -> p.getPolicyId().equals(policyId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Premium not found"));

        if (instalment.getStatus() == Domain.PremiumStatus.PAID) {
            throw new ApiException(HttpStatus.CONFLICT, "A paid premium cannot be waived");
        }

        instalment.setStatus(Domain.PremiumStatus.WAIVED);
        premiums.save(instalment);
        refreshPolicyDates(policy);

        audit.record(AuditAction.PREMIUM_WAIVED, ENTITY, instalment.getId().toString(),
                caller.getOrganizationId(), caller.getId(), caller.getUsername(), "policy=" + policyId);
        return PremiumResponse.of(instalment);
    }

    /**
     * A policy in another tenant, or one belonging to another agent, is reported
     * as missing rather than forbidden.
     */
    private Policy requireVisiblePolicy(UUID policyId) {
        AuthUser caller = SecurityUtil.current();
        Policy policy = policies.findByIdAndOrganizationId(policyId, caller.getOrganizationId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Policy not found"));

        if (caller.role() == Domain.Role.AGENT && !caller.getId().equals(policy.getAgentId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Policy not found");
        }
        return policy;
    }

    private PremiumPayment newInstalment(Policy policy, LocalDate due, LocalDate today) {
        PremiumPayment instalment = new PremiumPayment();
        instalment.setOrganizationId(policy.getOrganizationId());
        instalment.setPolicyId(policy.getId());
        instalment.setAmount(policy.getPremiumAmount());
        instalment.setDueDate(due);
        instalment.setStatus(PremiumSchedule.statusOn(due, today));
        return instalment;
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
