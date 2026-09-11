package com.policypulse.config;

import com.policypulse.common.Domain;
import com.policypulse.conversations.Conversation;
import com.policypulse.conversations.ConversationMessage;
import com.policypulse.conversations.ConversationMessageRepository;
import com.policypulse.conversations.ConversationRepository;
import com.policypulse.customers.Customer;
import com.policypulse.customers.CustomerRepository;
import com.policypulse.followups.FollowUp;
import com.policypulse.followups.FollowUpReason;
import com.policypulse.followups.FollowUpRepository;
import com.policypulse.organizations.Organization;
import com.policypulse.organizations.OrganizationZones;
import com.policypulse.policies.Policy;
import com.policypulse.policies.PolicyRepository;
import com.policypulse.premiums.PremiumPayment;
import com.policypulse.premiums.PremiumPaymentRepository;
import com.policypulse.premiums.PremiumSchedule;
import com.policypulse.premiums.PremiumService;
import com.policypulse.tasks.HumanTask;
import com.policypulse.tasks.HumanTaskRepository;
import com.policypulse.users.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * A small agency's book of business, so the app has something to be about on a
 * first run.
 *
 * <p>Every date is worked out from the tenant's today rather than written down,
 * so the demo is the same age whenever it is seeded: a premium that fell due last
 * week is still last week's next year. Otherwise the dashboard is empty a month
 * after the fixtures were written, which is when a demo is least forgiving.
 *
 * <p>The people are obviously invented. Names are ordinary but the addresses are
 * all {@code .local} and {@code example.test}, which cannot be delivered to, so
 * nothing here can turn into a message to somebody real if this is ever run where
 * it should not be.
 *
 * <p>The point is coverage rather than volume: enough that every page has
 * something on it, and that the interesting states — a premium overdue, a promise
 * made, a claim waiting to be checked, somebody who has opted out — are all
 * visible without anybody having to construct them.
 */
@Component
public class DemoBook {
    private static final Logger log = LoggerFactory.getLogger(DemoBook.class);

    private static final BigDecimal[] PREMIUMS = {
            new BigDecimal("4500.00"), new BigDecimal("12000.00"), new BigDecimal("2200.00"),
            new BigDecimal("36000.00"), new BigDecimal("7800.00")
    };

    private final CustomerRepository customers;
    private final PolicyRepository policies;
    private final PremiumPaymentRepository premiums;
    private final PremiumService premiumService;
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final FollowUpRepository followUps;
    private final HumanTaskRepository humanTasks;
    private final OrganizationZones zones;
    private final Clock clock;

    public DemoBook(CustomerRepository customers, PolicyRepository policies,
                    PremiumPaymentRepository premiums, PremiumService premiumService,
                    ConversationRepository conversations,
                    ConversationMessageRepository messages, FollowUpRepository followUps,
                    HumanTaskRepository humanTasks, OrganizationZones zones, Clock clock) {
        this.customers = customers;
        this.policies = policies;
        this.premiums = premiums;
        this.premiumService = premiumService;
        this.conversations = conversations;
        this.messages = messages;
        this.followUps = followUps;
        this.humanTasks = humanTasks;
        this.zones = zones;
        this.clock = clock;
    }

    /**
     * One customer's situation, so the list below reads as a set of cases.
     *
     * @param owing how many instalments that have already fallen due are unpaid.
     *              Zero is up to date; one leaves this month outstanding but
     *              nothing overdue; two puts them a month behind.
     * @param daysOff shifts the policy's start off a whole number of months, so
     *                the book is not everybody owing on the same date
     */
    private record Person(String first, String last, String phone, int agent,
                          int premium, int startedMonthsAgo, int daysOff,
                          int owing, boolean optedOut) {
    }

    /**
     * Chosen to cover the states the app has to show, not to look like a lot of
     * data. Started a whole number of months ago so that a monthly policy has an
     * instalment falling due today.
     */
    private static final List<Person> PEOPLE = List.of(
            // Up to date, nothing owing. The quiet majority.
            new Person("Meera", "Nair", "+919876500011", 0, 0, 24, 0, 0, false),
            new Person("Rajesh", "Kumar", "+919876500022", 0, 1, 36, 9, 0, false),
            // This month outstanding, nothing overdue yet. What detection picks
            // up today, and the commonest state in a working agency.
            new Person("Anita", "Desai", "+919876500033", 0, 2, 12, 0, 1, false),
            new Person("Kabir", "Bose", "+919876500088", 1, 1, 1, 0, 1, false),
            // A month behind, which is what the overdue total is made of.
            new Person("Vikram", "Shah", "+919876500044", 1, 3, 18, 4, 2, false),
            new Person("Priya", "Menon", "+919876500055", 1, 4, 30, 0, 2, false),
            new Person("Arjun", "Reddy", "+919876500066", 1, 0, 8, 17, 2, false),
            // Behind, and has asked not to be contacted. Reminders must skip
            // them anyway, which is the case worth being able to point at.
            new Person("Sunita", "Iyer", "+919876500077", 0, 2, 15, 11, 2, true)
    );

    /**
     * @param agents the agents to spread the book across, in order
     */
    public void fill(Organization org, List<AppUser> agents) {
        ZoneId zone = zones.zoneOf(org);
        LocalDate today = LocalDate.now(clock.withZone(zone));

        // Two people behind: one who made a promise, and one who says they have
        // already paid. Keeping them separate matters, because a claimed payment
        // suppresses the broken-promise escalation — correctly — and putting both
        // on one customer would demonstrate neither.
        Customer promiser = null;
        Policy promised = null;
        Customer claimer = null;
        Policy claimed = null;

        for (int i = 0; i < PEOPLE.size(); i++) {
            Person person = PEOPLE.get(i);
            AppUser agent = agents.get(person.agent() % agents.size());

            Customer customer = customer(org, agent, person, i);
            Policy policy = policy(org, customer, agent, person, today);
            schedule(org, policy, person, today);

            if (person.first().equals("Priya")) {
                promiser = customer;
                promised = policy;
            }
            if (person.first().equals("Vikram")) {
                claimer = customer;
                claimed = policy;
            }
        }

        brokenPromise(org, promiser, promised, zone, today);
        claimedPayment(org, claimer, claimed);
        log.info("Seeded {} demo customers with policies, premiums, a broken promise "
                + "and a claimed payment waiting to be checked", PEOPLE.size());
    }

    private Customer customer(Organization org, AppUser agent, Person person, int index) {
        Customer customer = new Customer();
        customer.setOrganizationId(org.getId());
        customer.setAssignedAgentId(agent.getId());
        customer.setCustomerNumber("DEMO-%03d".formatted(index + 1));
        customer.setFirstName(person.first());
        customer.setLastName(person.last());
        customer.setPhone(person.phone());
        // Undeliverable on purpose: a demo must not be able to write to anybody.
        customer.setEmail("%s.%s@example.test".formatted(
                person.first().toLowerCase(), person.last().toLowerCase()));
        customer.setPreferredLanguage("English");
        customer.setStatus(Domain.EntityStatus.ACTIVE);
        customer.setCommunicationConsent(!person.optedOut());
        customer.setOptedOut(person.optedOut());
        return customers.save(customer);
    }

    private Policy policy(Organization org, Customer customer, AppUser agent, Person person,
                          LocalDate today) {
        LocalDate start = today.minusMonths(person.startedMonthsAgo()).minusDays(person.daysOff());

        Policy policy = new Policy();
        policy.setOrganizationId(org.getId());
        policy.setCustomerId(customer.getId());
        policy.setAgentId(agent.getId());
        policy.setPolicyNumber("DEMO-POL-%s".formatted(customer.getCustomerNumber().substring(5)));
        policy.setInsuranceProvider("Example Life");
        policy.setPolicyType(person.premium() % 2 == 0 ? "TERM" : "ENDOWMENT");
        policy.setCurrencyCode("INR");
        policy.setPremiumAmount(PREMIUMS[person.premium()]);
        policy.setPremiumFrequency(Domain.PremiumFrequency.MONTHLY);
        policy.setPolicyStartDate(start);
        policy.setPolicyEndDate(start.plusYears(10));
        policy.setStatus(Domain.PolicyStatus.ACTIVE);
        return policies.save(policy);
    }

    /**
     * The instalments up to today, and the next few. Everything that has fallen
     * due is paid except the last few, however many this person is meant to be
     * behind by.
     */
    private void schedule(Organization org, Policy policy, Person person, LocalDate today) {
        // The whole term, the way a policy created through the application gets
        // one. Stopping a couple of months out left a policy that says it runs to
        // 2035 showing eighteen instalments, which is the demo contradicting
        // itself in the one place somebody is most likely to look closely.
        List<LocalDate> dates = PremiumSchedule.dueDates(
                policy.getPolicyStartDate(), policy.getPolicyEndDate(), policy.getPremiumFrequency());

        int lastPast = (int) dates.stream().filter(d -> !d.isAfter(today)).count() - 1;

        for (int i = 0; i < dates.size(); i++) {
            LocalDate due = dates.get(i);
            boolean past = !due.isAfter(today);
            // Counted back from the most recent one that has fallen due, so a
            // customer owing one is behind by this month and one owing two is
            // behind by a month — not by however long the policy has run.
            boolean owing = past && i > lastPast - person.owing();

            PremiumPayment instalment = new PremiumPayment();
            instalment.setOrganizationId(org.getId());
            instalment.setPolicyId(policy.getId());
            instalment.setAmount(policy.getPremiumAmount());
            instalment.setDueDate(due);

            if (past && !owing) {
                instalment.setStatus(Domain.PremiumStatus.PAID);
                instalment.setPaidDate(due);
                instalment.setPaymentMethod("UPI");
            } else {
                instalment.setStatus(PremiumSchedule.statusOn(due, today));
            }
            premiums.save(instalment);
        }

        // Asked of the same code the rest of the application uses, rather than
        // worked out again here. Two answers to one question is how they come to
        // disagree.
        premiumService.refreshPolicyDates(policy);
    }

    /**
     * One chase already in progress: a call that was answered, the promise made
     * on it, and a claimed payment waiting for somebody to check it. Between them
     * these are what the conversations, follow-ups and tasks pages are for.
     */
    /**
     * A call that was answered, and the promise made on it. The day has passed
     * and the money has not arrived, so the first run of the follow-up engine
     * escalates it — which is the behaviour worth seeing rather than describing.
     */
    private void brokenPromise(Organization org, Customer customer, Policy policy,
                               ZoneId zone, LocalDate today) {
        if (customer == null || policy == null) {
            return;
        }

        LocalDate promisedFor = today.minusDays(1);
        Instant called = ZonedDateTime.of(today.minusDays(3), LocalTime.of(11, 15), zone).toInstant();

        Conversation call = new Conversation();
        call.setOrganizationId(org.getId());
        call.setCustomerId(customer.getId());
        call.setPolicyId(policy.getId());
        call.setAgentId(customer.getAssignedAgentId());
        call.setChannel(Domain.Channel.VOICE);
        call.setDirection(Domain.ConversationDirection.OUTBOUND);
        call.setStatus(Domain.ConversationStatus.COMPLETED);
        call.setStartedAt(called);
        call.setEndedAt(called.plusSeconds(74));
        call.setDurationSeconds(74);
        call.setSummary("Customer said they would pay within two days.");
        call.setOutcome(FollowUpReason.PAYMENT_COMMITMENT.name());
        conversations.save(call);

        line(call, "ASSISTANT", "Hello %s, your premium of INR %s is overdue."
                .formatted(customer.getFirstName(), policy.getPremiumAmount().toPlainString()), called);
        line(call, "CUSTOMER", "Yes, I know. I will pay it the day after tomorrow.",
                called.plusSeconds(20));
        line(call, "ASSISTANT", "Thank you, I have noted that.", called.plusSeconds(40));

        FollowUp followUp = new FollowUp();
        followUp.setOrganizationId(org.getId());
        followUp.setCustomerId(customer.getId());
        followUp.setPolicyId(policy.getId());
        followUp.setConversationId(call.getId());
        followUp.setAssignedAgentId(customer.getAssignedAgentId());
        followUp.setReason(FollowUpReason.PAYMENT_COMMITMENT.name());
        followUp.setCommitmentDate(promisedFor);
        followUp.setDueAt(ZonedDateTime.of(promisedFor, LocalTime.of(9, 0), zone).toInstant());
        followUp.setStatus(Domain.FollowUpStatus.OPEN);
        followUps.save(followUp);
    }

    /**
     * Somebody who says they have already paid. The instalment is flagged and a
     * person is asked to check it against the books; nothing marks it as
     * received, which is the one thing the assistant may never do by itself.
     */
    private void claimedPayment(Organization org, Customer customer, Policy policy) {
        if (customer == null || policy == null) {
            return;
        }

        premiums.findByPolicyIdOrderByDueDateAsc(policy.getId()).stream()
                .filter(p -> p.getStatus() == Domain.PremiumStatus.OVERDUE)
                .findFirst()
                .ifPresent(instalment -> {
                    instalment.setVerificationPending(true);
                    premiums.save(instalment);

                    HumanTask task = new HumanTask();
                    task.setOrganizationId(org.getId());
                    task.setCustomerId(customer.getId());
                    task.setPolicyId(policy.getId());
                    task.setAssignedAgentId(customer.getAssignedAgentId());
                    task.setPriority(Domain.HumanTaskPriority.HIGH);
                    task.setReason("VERIFY_CLAIMED_PAYMENT");
                    task.setStatus(Domain.HumanTaskStatus.OPEN);
                    humanTasks.save(task);
                });
    }

    private void line(Conversation conversation, String sender, String text, Instant at) {
        ConversationMessage message = new ConversationMessage();
        message.setConversationId(conversation.getId());
        message.setSender(sender);
        message.setMessage(text);
        message.setTimestamp(at);
        messages.save(message);
    }
}
