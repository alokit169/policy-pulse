package com.policypulse.policies;

import com.policypulse.audit.AuditAction;
import com.policypulse.audit.AuditService;
import com.policypulse.common.ApiException;
import com.policypulse.common.Domain;
import com.policypulse.common.PageResponse;
import com.policypulse.customers.Customer;
import com.policypulse.customers.CustomerRepository;
import com.policypulse.premiums.PremiumService;
import com.policypulse.security.AuthUser;
import com.policypulse.security.SecurityUtil;
import com.policypulse.users.AppUser;
import com.policypulse.users.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class PolicyService {
    private static final String ENTITY = "Policy";

    private final PolicyRepository policies;
    private final CustomerRepository customers;
    private final UserRepository users;
    private final PremiumService premiums;
    private final AuditService audit;

    public PolicyService(PolicyRepository policies, CustomerRepository customers, UserRepository users,
                         PremiumService premiums, AuditService audit) {
        this.policies = policies;
        this.customers = customers;
        this.users = users;
        this.premiums = premiums;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<PolicyResponse> search(Domain.PolicyStatus status, Pageable pageable) {
        AuthUser caller = SecurityUtil.current();
        UUID orgId = caller.getOrganizationId();
        boolean agent = caller.role() == Domain.Role.AGENT;

        Page<Policy> page;
        if (agent) {
            page = status == null
                    ? policies.findByOrganizationIdAndAgentId(orgId, caller.getId(), pageable)
                    : policies.findByOrganizationIdAndAgentIdAndStatus(orgId, caller.getId(), status, pageable);
        } else {
            page = status == null
                    ? policies.findByOrganizationId(orgId, pageable)
                    : policies.findByOrganizationIdAndStatus(orgId, status, pageable);
        }

        return new PageResponse<>(
                page.getContent().stream().map(PolicyResponse::of).toList(),
                page.getTotalElements(), page.getNumber(), page.getSize());
    }

    @Transactional(readOnly = true)
    public PolicyResponse get(UUID id) {
        return PolicyResponse.of(loadVisible(id));
    }

    /** Policies of one customer, subject to the same visibility rules. */
    @Transactional(readOnly = true)
    public java.util.List<PolicyResponse> forCustomer(UUID customerId) {
        AuthUser caller = SecurityUtil.current();
        requireVisibleCustomer(caller, customerId);

        return policies.findByCustomerIdAndOrganizationId(customerId, caller.getOrganizationId()).stream()
                .filter(p -> caller.role() != Domain.Role.AGENT || caller.getId().equals(p.getAgentId()))
                .map(PolicyResponse::of)
                .toList();
    }

    @Transactional
    public PolicyResponse create(PolicyRequest request) {
        AuthUser caller = SecurityUtil.current();
        UUID orgId = caller.getOrganizationId();

        Customer customer = requireVisibleCustomer(caller, request.customerId());

        Policy policy = new Policy();
        policy.setOrganizationId(orgId);
        policy.setCustomerId(customer.getId());
        policy.setAgentId(resolveAgent(caller, request.agentId(), customer));
        policy.setPolicyNumber(resolvePolicyNumber(orgId, request.policyNumber()));
        policy.setStatus(Domain.PolicyStatus.ACTIVE);
        policy.setCurrencyCode("INR");
        apply(request, policy);
        policies.save(policy);

        premiums.regenerateSchedule(policy);

        audit.record(AuditAction.POLICY_CREATED, ENTITY, policy.getId().toString(),
                orgId, caller.getId(), caller.getUsername(), "customer=" + customer.getId());
        return PolicyResponse.of(policy);
    }

    @Transactional
    public PolicyResponse update(UUID id, PolicyRequest request) {
        AuthUser caller = SecurityUtil.current();
        Policy policy = loadVisible(id);

        // The customer a policy belongs to may be corrected, but only to another
        // customer the caller can already see.
        Customer customer = requireVisibleCustomer(caller, request.customerId());
        policy.setCustomerId(customer.getId());

        if (caller.role() != Domain.Role.AGENT && request.agentId() != null) {
            policy.setAgentId(resolveAgent(caller, request.agentId(), customer));
        }
        apply(request, policy);
        policies.save(policy);

        // Terms may have moved, so the schedule is rebuilt around what is settled.
        premiums.regenerateSchedule(policy);

        audit.record(AuditAction.POLICY_UPDATED, ENTITY, policy.getId().toString(),
                caller.getOrganizationId(), caller.getId(), caller.getUsername(), null);
        return PolicyResponse.of(policy);
    }

    @Transactional
    public PolicyResponse changeStatus(UUID id, Domain.PolicyStatus status) {
        AuthUser caller = SecurityUtil.current();
        Policy policy = loadVisible(id);

        Domain.PolicyStatus previous = policy.getStatus();
        policy.setStatus(status);
        policies.save(policy);

        audit.record(AuditAction.POLICY_STATUS_CHANGED, ENTITY, policy.getId().toString(),
                caller.getOrganizationId(), caller.getId(), caller.getUsername(),
                previous + " -> " + status);
        return PolicyResponse.of(policy);
    }

    private Policy loadVisible(UUID id) {
        AuthUser caller = SecurityUtil.current();
        Policy policy = policies.findByIdAndOrganizationId(id, caller.getOrganizationId())
                .orElseThrow(() -> notFound("Policy"));

        if (caller.role() == Domain.Role.AGENT && !caller.getId().equals(policy.getAgentId())) {
            throw notFound("Policy");
        }
        return policy;
    }

    /**
     * A policy may only ever point at a customer the caller can already see.
     * Without this a customer id from another tenant would attach a policy to a
     * record its owner cannot reach, and leak that the customer exists.
     */
    private Customer requireVisibleCustomer(AuthUser caller, UUID customerId) {
        Customer customer = customers.findByIdAndOrganizationId(customerId, caller.getOrganizationId())
                .orElseThrow(() -> notFound("Customer"));

        if (caller.role() == Domain.Role.AGENT && !caller.getId().equals(customer.getAssignedAgentId())) {
            throw notFound("Customer");
        }
        return customer;
    }

    /**
     * Agents hold their own policies. Anyone senior may assign within their own
     * organization; by default a policy follows the customer's agent, so the two
     * do not start out disagreeing.
     */
    private UUID resolveAgent(AuthUser caller, UUID requested, Customer customer) {
        if (caller.role() == Domain.Role.AGENT) {
            return caller.getId();
        }
        if (requested == null) {
            return customer.getAssignedAgentId();
        }
        AppUser assignee = users.findById(requested)
                .filter(u -> u.getOrganizationId().equals(caller.getOrganizationId()))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Assigned agent not found"));
        return assignee.getId();
    }

    private String resolvePolicyNumber(UUID orgId, String requested) {
        if (requested != null && !requested.isBlank()) {
            String trimmed = requested.trim();
            if (policies.findByOrganizationIdAndPolicyNumber(orgId, trimmed).isPresent()) {
                throw new ApiException(HttpStatus.CONFLICT, "Policy number is already in use");
            }
            return trimmed;
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = "P-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
            if (policies.findByOrganizationIdAndPolicyNumber(orgId, candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not allocate a policy number");
    }

    private void apply(PolicyRequest request, Policy policy) {
        if (request.policyStartDate() != null && request.policyEndDate() != null
                && request.policyEndDate().isBefore(request.policyStartDate())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Policy end date cannot be before its start date");
        }

        policy.setInsuranceProvider(request.insuranceProvider().trim());
        policy.setPolicyType(request.policyType().trim());
        policy.setPlanName(blankToNull(request.planName()));
        // Only set when supplied. Defaulting here would turn a USD policy into an
        // INR one on any update that omitted the field, leaving the amounts
        // untouched and the currency wrong.
        if (request.currencyCode() != null) {
            policy.setCurrencyCode(request.currencyCode());
        }
        policy.setSumAssured(request.sumAssured());
        policy.setPremiumAmount(request.premiumAmount());
        policy.setPremiumFrequency(request.premiumFrequency());
        policy.setPolicyStartDate(request.policyStartDate());
        policy.setPolicyEndDate(request.policyEndDate());
        policy.setMaturityDate(request.maturityDate());
        policy.setNomineeName(blankToNull(request.nomineeName()));
        policy.setBonusAmount(request.bonusAmount());
        policy.setMaturityAmount(request.maturityAmount());
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
