package com.policypulse.ai;

import com.policypulse.audit.AuditAction;
import com.policypulse.audit.AuditService;
import com.policypulse.common.ApiException;
import com.policypulse.common.Domain;
import com.policypulse.conversations.Conversation;
import com.policypulse.conversations.ConversationMessage;
import com.policypulse.conversations.ConversationMessageRepository;
import com.policypulse.conversations.ConversationService;
import com.policypulse.customers.Customer;
import com.policypulse.customers.CustomerRepository;
import com.policypulse.organizations.OrganizationZones;
import com.policypulse.premiums.PremiumPayment;
import com.policypulse.premiums.PremiumPaymentRepository;
import com.policypulse.security.AuthUser;
import com.policypulse.security.SecurityUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runs a conversation past the assistant and applies whatever the validation
 * layer permits.
 */
@Service
public class AnalysisService {
    private static final Set<Domain.PremiumStatus> SETTLED =
            EnumSet.of(Domain.PremiumStatus.PAID, Domain.PremiumStatus.WAIVED);

    private final AIProvider provider;
    private final ActionValidationService validation;
    private final ConversationService conversationService;
    private final ConversationMessageRepository messages;
    private final CustomerRepository customers;
    private final PremiumPaymentRepository premiums;
    private final OrganizationZones zones;
    private final AuditService audit;

    public AnalysisService(AIProvider provider, ActionValidationService validation,
                           ConversationService conversationService, ConversationMessageRepository messages,
                           CustomerRepository customers, PremiumPaymentRepository premiums,
                           OrganizationZones zones, AuditService audit) {
        this.provider = provider;
        this.validation = validation;
        this.conversationService = conversationService;
        this.messages = messages;
        this.customers = customers;
        this.premiums = premiums;
        this.zones = zones;
        this.audit = audit;
    }

    @Transactional
    public AnalysisResponse analyse(UUID conversationId) {
        AuthUser caller = SecurityUtil.current();

        // Reuses the conversation's own visibility rules, so analysis cannot be
        // used to reach a call the caller could not otherwise open.
        Conversation conversation = conversationService.loadVisible(conversationId);

        List<ConversationMessage> transcript =
                messages.findByConversationIdOrderByTimestampAsc(conversation.getId());
        if (transcript.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "There is nothing recorded to analyse");
        }

        IntentAnalysis analysis = provider.analyse(contextFor(conversation, transcript));
        ActionValidationService.Outcome outcome = validation.apply(conversation, analysis);

        audit.record(AuditAction.AI_ANALYSIS_RECORDED, "Conversation", conversation.getId().toString(),
                conversation.getOrganizationId(), caller.getId(), caller.getUsername(),
                "%s confidence=%.2f acted=%s".formatted(
                        outcome.intent(), outcome.confidence(), outcome.acted()));

        return new AnalysisResponse(
                provider.name(), outcome.intent(), outcome.confidence(), analysis.committedDate(),
                analysis.summary(), analysis.sentiment(), outcome.acted(), outcome.decision(),
                outcome.actions());
    }

    /** Only what is needed to read the call: no identifiers, no contact details. */
    private ConversationContext contextFor(Conversation conversation, List<ConversationMessage> transcript) {
        String firstName = customers.findById(conversation.getCustomerId())
                .map(Customer::getFirstName)
                .orElse(null);

        LocalDate dueOn = conversation.getPolicyId() == null ? null
                : premiums.findByPolicyIdOrderByDueDateAsc(conversation.getPolicyId()).stream()
                .filter(p -> !SETTLED.contains(p.getStatus()))
                .min(Comparator.comparing(PremiumPayment::getDueDate))
                .map(PremiumPayment::getDueDate)
                .orElse(null);

        return new ConversationContext(
                zones.today(conversation.getOrganizationId()),
                firstName,
                dueOn,
                transcript.stream()
                        .map(m -> new ConversationContext.Line(m.getSender(), m.getMessage()))
                        .toList());
    }
}
