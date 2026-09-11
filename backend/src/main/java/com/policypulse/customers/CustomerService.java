package com.policypulse.customers;

import com.policypulse.audit.AuditAction;
import com.policypulse.audit.AuditService;
import com.policypulse.common.ApiException;
import com.policypulse.common.Domain;
import com.policypulse.common.PageResponse;
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
public class CustomerService {
    private static final String ENTITY = "Customer";

    private final CustomerRepository customers;
    private final UserRepository users;
    private final AuditService audit;

    public CustomerService(CustomerRepository customers, UserRepository users, AuditService audit) {
        this.customers = customers;
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> search(String q, Domain.EntityStatus status,
                                                 UUID agentId, Pageable pageable) {
        AuthUser caller = SecurityUtil.current();

        // An agent only ever sees their own book, whatever they ask for.
        UUID effectiveAgentId = isAgent(caller) ? caller.getId() : agentId;

        Page<Customer> page = customers.search(
                caller.getOrganizationId(), effectiveAgentId, status, likePattern(q), pageable);

        return new PageResponse<>(
                page.getContent().stream().map(CustomerResponse::of).toList(),
                page.getTotalElements(),
                page.getNumber(),
                page.getSize());
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(UUID id) {
        return CustomerResponse.of(loadVisible(id));
    }

    @Transactional
    public CustomerResponse create(CustomerRequest request) {
        AuthUser caller = SecurityUtil.current();
        UUID orgId = caller.getOrganizationId();

        Customer customer = new Customer();
        customer.setOrganizationId(orgId);
        customer.setAssignedAgentId(resolveAssignee(caller, request.assignedAgentId()));
        customer.setCustomerNumber(resolveCustomerNumber(orgId, request.customerNumber()));
        customer.setStatus(Domain.EntityStatus.ACTIVE);
        customer.setCommunicationConsent(request.communicationConsent() == null || request.communicationConsent());
        apply(request, customer);

        requirePhoneIsFree(orgId, customer.getPhone(), null);
        customers.save(customer);

        audit.record(AuditAction.CUSTOMER_CREATED, ENTITY, customer.getId().toString(),
                orgId, caller.getId(), caller.getUsername(), null);
        return CustomerResponse.of(customer);
    }

    @Transactional
    public CustomerResponse update(UUID id, CustomerRequest request) {
        AuthUser caller = SecurityUtil.current();
        Customer customer = loadVisible(id);

        // Reassignment is a management action; an agent keeps their own customer.
        if (!isAgent(caller) && request.assignedAgentId() != null) {
            customer.setAssignedAgentId(resolveAssignee(caller, request.assignedAgentId()));
        }
        if (request.communicationConsent() != null) {
            customer.setCommunicationConsent(request.communicationConsent());
        }
        apply(request, customer);

        requirePhoneIsFree(caller.getOrganizationId(), customer.getPhone(), customer.getId());
        customers.save(customer);

        audit.record(AuditAction.CUSTOMER_UPDATED, ENTITY, customer.getId().toString(),
                caller.getOrganizationId(), caller.getId(), caller.getUsername(), null);
        return CustomerResponse.of(customer);
    }

    /**
     * Archives rather than deletes. Policies and premium history reference
     * customers, so removing the row would orphan them.
     */
    @Transactional
    public void archive(UUID id) {
        AuthUser caller = SecurityUtil.current();
        Customer customer = loadVisible(id);

        customer.setStatus(Domain.EntityStatus.INACTIVE);
        customers.save(customer);

        audit.record(AuditAction.CUSTOMER_ARCHIVED, ENTITY, customer.getId().toString(),
                caller.getOrganizationId(), caller.getId(), caller.getUsername(), null);
    }

    @Transactional
    public CustomerResponse restore(UUID id) {
        AuthUser caller = SecurityUtil.current();
        Customer customer = loadVisible(id);

        customer.setStatus(Domain.EntityStatus.ACTIVE);
        customers.save(customer);

        audit.record(AuditAction.CUSTOMER_RESTORED, ENTITY, customer.getId().toString(),
                caller.getOrganizationId(), caller.getId(), caller.getUsername(), null);
        return CustomerResponse.of(customer);
    }

    /**
     * Loads a customer the caller is allowed to see, or reports it as missing.
     *
     * <p>A customer in another tenant, and one belonging to another agent, both
     * produce 404 rather than 403. Answering 403 would confirm the record exists.
     */
    private Customer loadVisible(UUID id) {
        AuthUser caller = SecurityUtil.current();
        Customer customer = customers.findByIdAndOrganizationId(id, caller.getOrganizationId())
                .orElseThrow(CustomerService::notFound);

        if (isAgent(caller) && !caller.getId().equals(customer.getAssignedAgentId())) {
            throw notFound();
        }
        return customer;
    }

    /**
     * Agents may only ever hold their own customers. Anyone senior may assign to
     * an agent in their own organization; a user id from another tenant is
     * rejected rather than silently accepted.
     */
    private UUID resolveAssignee(AuthUser caller, UUID requested) {
        if (isAgent(caller) || requested == null) {
            return caller.getId();
        }
        AppUser assignee = users.findById(requested)
                .filter(u -> u.getOrganizationId().equals(caller.getOrganizationId()))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Assigned agent not found"));
        return assignee.getId();
    }

    private String resolveCustomerNumber(UUID orgId, String requested) {
        if (requested != null && !requested.isBlank()) {
            String trimmed = requested.trim();
            if (customers.existsByOrganizationIdAndCustomerNumberIgnoreCase(orgId, trimmed)) {
                throw new ApiException(HttpStatus.CONFLICT, "Customer number is already in use");
            }
            return trimmed;
        }
        // Random rather than sequential: a per-tenant counter would need locking
        // to stay correct under concurrent creates. The unique constraint is the
        // real guarantee either way.
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = "C-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
            if (!customers.existsByOrganizationIdAndCustomerNumberIgnoreCase(orgId, candidate)) {
                return candidate;
            }
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not allocate a customer number");
    }

    /** The schema makes phone unique per tenant; fail with a usable message. */
    private void requirePhoneIsFree(UUID orgId, String phone, UUID selfId) {
        customers.findByOrganizationIdAndPhone(orgId, phone)
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw new ApiException(HttpStatus.CONFLICT, "Another customer already uses this phone number");
                });
    }

    private void apply(CustomerRequest request, Customer customer) {
        customer.setFirstName(request.firstName().trim());
        customer.setLastName(request.lastName().trim());
        customer.setPhone(request.phone().trim());
        customer.setAlternatePhone(blankToNull(request.alternatePhone()));
        customer.setEmail(blankToNull(request.email()));
        customer.setDateOfBirth(request.dateOfBirth());
        customer.setAddress(blankToNull(request.address()));
        customer.setPreferredContactTime(blankToNull(request.preferredContactTime()));
        customer.setNotes(blankToNull(request.notes()));

        String language = blankToNull(request.preferredLanguage());
        if (language != null) {
            customer.setPreferredLanguage(language);
        }
    }

    private static boolean isAgent(AuthUser caller) {
        return caller.role() == Domain.Role.AGENT;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "Customer not found");
    }

    /** "%" when no term was given, so the query matches every row. */
    private static String likePattern(String q) {
        String term = blankToNull(q);
        return term == null ? "%" : "%" + term.toLowerCase(Locale.ROOT) + "%";
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
