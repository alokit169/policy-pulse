package com.policypulse.conversations;

import com.policypulse.audit.AuditAction;
import com.policypulse.audit.AuditService;
import com.policypulse.common.ApiException;
import com.policypulse.common.Domain;
import com.policypulse.common.PageResponse;
import com.policypulse.customers.Customer;
import com.policypulse.customers.CustomerRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {
    private static final String ENTITY = "Conversation";

    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final CustomerRepository customers;
    private final PolicyRepository policies;
    private final AuditService audit;
    private final Clock clock;

    public ConversationService(ConversationRepository conversations, ConversationMessageRepository messages,
                               CustomerRepository customers, PolicyRepository policies,
                               AuditService audit, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.customers = customers;
        this.policies = policies;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<ConversationResponse> search(Pageable pageable) {
        AuthUser caller = SecurityUtil.current();
        Page<Conversation> page = conversations.findByOrganizationId(caller.getOrganizationId(), pageable);

        // Listings omit transcripts: a page of twenty conversations would
        // otherwise pull every line of every one of them.
        return new PageResponse<>(
                page.getContent().stream()
                        .filter(c -> maySee(caller, c))
                        .map(ConversationResponse::summary)
                        .toList(),
                page.getTotalElements(), page.getNumber(), page.getSize());
    }

    @Transactional(readOnly = true)
    public ConversationResponse get(UUID id) {
        Conversation conversation = loadVisible(id);
        return ConversationResponse.of(conversation, transcriptOf(conversation.getId()));
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> forCustomer(UUID customerId) {
        AuthUser caller = SecurityUtil.current();
        requireVisibleCustomer(caller, customerId);

        return conversations.findByCustomerIdOrderByStartedAtDesc(customerId).stream()
                .filter(c -> c.getOrganizationId().equals(caller.getOrganizationId()))
                .filter(c -> maySee(caller, c))
                .map(ConversationResponse::summary)
                .toList();
    }

    @Transactional
    public ConversationResponse start(ConversationRequest request) {
        AuthUser caller = SecurityUtil.current();
        Customer customer = requireVisibleCustomer(caller, request.customerId());

        Conversation conversation = new Conversation();
        conversation.setOrganizationId(caller.getOrganizationId());
        conversation.setCustomerId(customer.getId());
        conversation.setPolicyId(resolvePolicy(caller, request.policyId(), customer));
        conversation.setAgentId(caller.getId());
        conversation.setChannel(request.channel());
        conversation.setDirection(request.direction());
        conversation.setStatus(Domain.ConversationStatus.IN_PROGRESS);
        conversation.setStartedAt(Instant.now(clock));
        conversation.setSummary(blankToNull(request.summary()));
        conversation.setSentiment(blankToNull(request.sentiment()));
        conversation.setOutcome(blankToNull(request.outcome()));
        conversations.save(conversation);

        audit.record(AuditAction.CONVERSATION_STARTED, ENTITY, conversation.getId().toString(),
                caller.getOrganizationId(), caller.getId(), caller.getUsername(),
                "customer=" + customer.getId());
        return ConversationResponse.of(conversation, List.of());
    }

    @Transactional
    public MessageResponse addMessage(UUID conversationId, MessageRequest request) {
        Conversation conversation = loadVisible(conversationId);

        if (conversation.getStatus() == Domain.ConversationStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "This conversation is already closed");
        }

        ConversationMessage message = new ConversationMessage();
        message.setConversationId(conversation.getId());
        message.setSender(request.sender().name());
        message.setMessage(request.message().trim());
        message.setTranscriptReference(blankToNull(request.transcriptReference()));
        message.setTimestamp(Instant.now(clock));
        messages.save(message);

        return MessageResponse.of(message);
    }

    /**
     * Closes a conversation and records how it went.
     *
     * <p>The duration is measured from the stored start rather than taken from
     * the caller, so it cannot disagree with the timestamps either side of it.
     */
    @Transactional
    public ConversationResponse close(UUID id, CloseConversationRequest request) {
        AuthUser caller = SecurityUtil.current();
        Conversation conversation = loadVisible(id);

        if (conversation.getStatus() == Domain.ConversationStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "This conversation is already closed");
        }

        Instant endedAt = Instant.now(clock);
        conversation.setEndedAt(endedAt);
        conversation.setDurationSeconds(
                (int) Math.max(0, Duration.between(conversation.getStartedAt(), endedAt).toSeconds()));
        conversation.setStatus(request.status() == null ? Domain.ConversationStatus.COMPLETED : request.status());
        if (blankToNull(request.summary()) != null) conversation.setSummary(request.summary().trim());
        if (blankToNull(request.outcome()) != null) conversation.setOutcome(request.outcome().trim());
        if (blankToNull(request.sentiment()) != null) conversation.setSentiment(request.sentiment().trim());
        conversations.save(conversation);

        audit.record(AuditAction.CONVERSATION_CLOSED, ENTITY, conversation.getId().toString(),
                caller.getOrganizationId(), caller.getId(), caller.getUsername(),
                "outcome=" + conversation.getOutcome());
        return ConversationResponse.of(conversation, transcriptOf(conversation.getId()));
    }

    private List<MessageResponse> transcriptOf(UUID conversationId) {
        return messages.findByConversationIdOrderByTimestampAsc(conversationId).stream()
                .map(MessageResponse::of)
                .toList();
    }

    /**
     * A conversation in another tenant, or one held by another agent, is reported
     * as missing rather than forbidden.
     *
     * <p>Public so analysis reuses exactly these rules rather than restating them,
     * which would let the two drift apart and open a way to reach a call the
     * caller could not otherwise see.
     */
    public Conversation loadVisible(UUID id) {
        AuthUser caller = SecurityUtil.current();
        Conversation conversation = conversations.findByIdAndOrganizationId(id, caller.getOrganizationId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conversation not found"));

        if (!maySee(caller, conversation)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Conversation not found");
        }
        return conversation;
    }

    /** An agent sees conversations they held, or that concern their own customers. */
    private boolean maySee(AuthUser caller, Conversation conversation) {
        if (caller.role() != Domain.Role.AGENT) return true;
        if (caller.getId().equals(conversation.getAgentId())) return true;

        return customers.findById(conversation.getCustomerId())
                .map(c -> caller.getId().equals(c.getAssignedAgentId()))
                .orElse(false);
    }

    private Customer requireVisibleCustomer(AuthUser caller, UUID customerId) {
        Customer customer = customers.findByIdAndOrganizationId(customerId, caller.getOrganizationId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Customer not found"));

        if (caller.role() == Domain.Role.AGENT && !caller.getId().equals(customer.getAssignedAgentId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Customer not found");
        }
        return customer;
    }

    /**
     * A conversation may only cite a policy in the caller's tenant that belongs to
     * the same customer, so a record cannot be quietly attached to someone else's.
     */
    private UUID resolvePolicy(AuthUser caller, UUID policyId, Customer customer) {
        if (policyId == null) return null;

        Policy policy = policies.findByIdAndOrganizationId(policyId, caller.getOrganizationId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Policy not found"));

        if (!policy.getCustomerId().equals(customer.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That policy belongs to a different customer");
        }
        return policy.getId();
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
