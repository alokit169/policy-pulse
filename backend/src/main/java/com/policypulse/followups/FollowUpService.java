package com.policypulse.followups;

import com.policypulse.audit.AuditAction;
import com.policypulse.audit.AuditService;
import com.policypulse.common.ApiException;
import com.policypulse.common.Domain;
import com.policypulse.common.PageResponse;
import com.policypulse.conversations.Conversation;
import com.policypulse.conversations.ConversationRepository;
import com.policypulse.customers.Customer;
import com.policypulse.customers.CustomerRepository;
import com.policypulse.organizations.OrganizationZones;
import com.policypulse.policies.Policy;
import com.policypulse.policies.PolicyRepository;
import com.policypulse.security.AuthUser;
import com.policypulse.security.SecurityUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class FollowUpService {
    private static final String ENTITY = "FollowUp";

    /** Statuses that still need someone to do something. */
    private static final List<Domain.FollowUpStatus> OUTSTANDING =
            List.of(Domain.FollowUpStatus.OPEN, Domain.FollowUpStatus.DUE);

    /** When in the customer's day a commitment comes due. */
    private static final LocalTime START_OF_WORKING_DAY = LocalTime.of(9, 0);

    private final FollowUpRepository followUps;
    private final FollowUpRunner runner;
    private final CustomerRepository customers;
    private final PolicyRepository policies;
    private final ConversationRepository conversations;
    private final OrganizationZones zones;
    private final AuditService audit;
    private final Clock clock;

    public FollowUpService(FollowUpRepository followUps, FollowUpRunner runner,
                           CustomerRepository customers,
                           PolicyRepository policies, ConversationRepository conversations,
                           OrganizationZones zones, AuditService audit, Clock clock) {
        this.followUps = followUps;
        this.runner = runner;
        this.customers = customers;
        this.policies = policies;
        this.conversations = conversations;
        this.zones = zones;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<FollowUpResponse> search(Domain.FollowUpStatus status, Pageable pageable) {
        AuthUser caller = SecurityUtil.current();
        UUID orgId = caller.getOrganizationId();
        boolean ownOnly = caller.role() == Domain.Role.AGENT;

        Page<FollowUp> page;
        if (ownOnly) {
            page = status == null
                    ? followUps.findByOrganizationIdAndAssignedAgentIdOrderByDueAtAsc(orgId, caller.getId(), pageable)
                    : followUps.findByOrganizationIdAndAssignedAgentIdAndStatusOrderByDueAtAsc(
                            orgId, caller.getId(), status, pageable);
        } else {
            page = status == null
                    ? followUps.findByOrganizationIdOrderByDueAtAsc(orgId, pageable)
                    : followUps.findByOrganizationIdAndStatusOrderByDueAtAsc(orgId, status, pageable);
        }

        return new PageResponse<>(
                page.getContent().stream().map(FollowUpResponse::of).toList(),
                page.getTotalElements(), page.getNumber(), page.getSize());
    }

    @Transactional(readOnly = true)
    public FollowUpResponse get(UUID id) {
        return FollowUpResponse.of(loadVisible(id));
    }

    @Transactional(readOnly = true)
    public List<FollowUpResponse> forCustomer(UUID customerId) {
        AuthUser caller = SecurityUtil.current();
        requireVisibleCustomer(caller, customerId);

        return followUps.findByCustomerIdOrderByDueAtAsc(customerId).stream()
                .filter(f -> f.getOrganizationId().equals(caller.getOrganizationId()))
                .map(FollowUpResponse::of)
                .toList();
    }

    @Transactional
    public FollowUpResponse create(FollowUpRequest request) {
        AuthUser caller = SecurityUtil.current();
        Customer customer = requireVisibleCustomer(caller, request.customerId());

        // A payment commitment without a date is not a commitment: there would be
        // nothing to come back on.
        if (request.reason() == FollowUpReason.PAYMENT_COMMITMENT && request.commitmentDate() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A payment commitment needs the date the customer gave");
        }

        LocalDate today = zones.today(caller.getOrganizationId());
        if (request.commitmentDate() != null && request.commitmentDate().isBefore(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A commitment date cannot be in the past");
        }

        FollowUp followUp = new FollowUp();
        followUp.setOrganizationId(caller.getOrganizationId());
        followUp.setCustomerId(customer.getId());
        followUp.setPolicyId(resolvePolicy(caller, request.policyId(), customer));
        followUp.setConversationId(resolveConversation(caller, request.conversationId(), customer));
        // Follows the customer, so the person who owns the relationship owns the
        // work, whoever happened to log it.
        followUp.setAssignedAgentId(customer.getAssignedAgentId());
        followUp.setReason(request.reason().name());
        followUp.setCommitmentDate(request.commitmentDate());
        followUp.setDueAt(dueAt(caller.getOrganizationId(), request.commitmentDate(), today));
        followUp.setStatus(Domain.FollowUpStatus.OPEN);
        followUp.setNotes(blankToNull(request.notes()));
        followUps.save(followUp);

        audit.record(AuditAction.FOLLOW_UP_CREATED, ENTITY, followUp.getId().toString(),
                caller.getOrganizationId(), caller.getId(), caller.getUsername(),
                "reason=" + followUp.getReason());
        return FollowUpResponse.of(followUp);
    }

    /**
     * Works through the caller's own tenant now. The scheduler covers every
     * tenant on its own; this exists so the effect of a payment being recorded
     * can be seen without waiting for the next sweep.
     */
    public FollowUpEngine.Result runNow() {
        AuthUser caller = SecurityUtil.current();
        if (caller.role() == Domain.Role.AGENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only managers can run the follow-up engine");
        }
        return runner.runFor(caller.getOrganizationId());
    }

    @Transactional
    public FollowUpResponse complete(UUID id, String notes) {
        return settle(id, Domain.FollowUpStatus.COMPLETED, AuditAction.FOLLOW_UP_COMPLETED, notes);
    }

    @Transactional
    public FollowUpResponse cancel(UUID id, String notes) {
        return settle(id, Domain.FollowUpStatus.CANCELLED, AuditAction.FOLLOW_UP_CANCELLED, notes);
    }

    private FollowUpResponse settle(UUID id, Domain.FollowUpStatus status, AuditAction action, String notes) {
        AuthUser caller = SecurityUtil.current();
        FollowUp followUp = loadVisible(id);

        if (!OUTSTANDING.contains(followUp.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "This follow-up is already closed");
        }

        followUp.setStatus(status);
        if (blankToNull(notes) != null) followUp.setNotes(notes.trim());
        followUps.save(followUp);

        audit.record(action, ENTITY, followUp.getId().toString(),
                caller.getOrganizationId(), caller.getId(), caller.getUsername(), null);
        return FollowUpResponse.of(followUp);
    }

    /**
     * Commitments come due at the start of the customer's working day, in their
     * own timezone, so a follow-up is never waiting for someone at midnight. With
     * no date given, it is due now, since it has already been noticed.
     */
    private Instant dueAt(UUID organizationId, LocalDate commitmentDate, LocalDate today) {
        if (commitmentDate == null) {
            return Instant.now(clock);
        }
        return ZonedDateTime.of(commitmentDate, START_OF_WORKING_DAY, zones.zoneOf(organizationId)).toInstant();
    }

    private FollowUp loadVisible(UUID id) {
        AuthUser caller = SecurityUtil.current();
        FollowUp followUp = followUps.findByIdAndOrganizationId(id, caller.getOrganizationId())
                .orElseThrow(() -> notFound("Follow-up"));

        if (caller.role() == Domain.Role.AGENT && !caller.getId().equals(followUp.getAssignedAgentId())) {
            throw notFound("Follow-up");
        }
        return followUp;
    }

    private Customer requireVisibleCustomer(AuthUser caller, UUID customerId) {
        Customer customer = customers.findByIdAndOrganizationId(customerId, caller.getOrganizationId())
                .orElseThrow(() -> notFound("Customer"));

        if (caller.role() == Domain.Role.AGENT && !caller.getId().equals(customer.getAssignedAgentId())) {
            throw notFound("Customer");
        }
        return customer;
    }

    private UUID resolvePolicy(AuthUser caller, UUID policyId, Customer customer) {
        if (policyId == null) return null;

        Policy policy = policies.findByIdAndOrganizationId(policyId, caller.getOrganizationId())
                .orElseThrow(() -> notFound("Policy"));

        if (!policy.getCustomerId().equals(customer.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That policy belongs to a different customer");
        }
        return policy.getId();
    }

    /** The conversation must be in the same tenant and about the same customer. */
    private UUID resolveConversation(AuthUser caller, UUID conversationId, Customer customer) {
        if (conversationId == null) return null;

        Conversation conversation = conversations
                .findByIdAndOrganizationId(conversationId, caller.getOrganizationId())
                .orElseThrow(() -> notFound("Conversation"));

        if (!conversation.getCustomerId().equals(customer.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That conversation was with a different customer");
        }
        return conversation.getId();
    }

    private static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, what + " not found");
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
