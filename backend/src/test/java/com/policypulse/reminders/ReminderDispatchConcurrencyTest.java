package com.policypulse.reminders;

import com.policypulse.AbstractIntegrationTest;
import com.policypulse.FixedClockConfiguration;
import com.policypulse.common.Domain;
import com.policypulse.conversations.Conversation;
import com.policypulse.conversations.ConversationRepository;
import com.policypulse.customers.Customer;
import com.policypulse.customers.CustomerRepository;
import com.policypulse.organizations.Organization;
import com.policypulse.policies.Policy;
import com.policypulse.policies.PolicyRepository;
import com.policypulse.voice.StubVoiceProvider;
import com.policypulse.users.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two sweeps can want the same reminder at once: the hourly one and a manager
 * running detection by hand. Checking the status in memory and then writing it
 * lets both through, and on a voice reminder that means ringing the customer
 * twice — the call is already placed by the time the second one notices.
 *
 * <p>The same shape as the premium-payment race, and the same lesson: whichever
 * check decides may only be made where the row lock is.
 */
class ReminderDispatchConcurrencyTest extends AbstractIntegrationTest {

    private static final AtomicLong PHONE_SEQ = new AtomicLong(System.nanoTime());

    @Autowired private StubVoiceProvider voice;
    @Autowired private ReminderDispatchService dispatch;
    @Autowired private ReminderRepository reminders;
    @Autowired private ReminderConfigurationRepository configurations;
    @Autowired private CustomerRepository customers;
    @Autowired private PolicyRepository policies;
    @Autowired private ConversationRepository conversations;

    @Test
    void aReminderDispatchedByTwoSweepsAtOncePlacesOneCall() throws Exception {
        Organization org = new Organization();
        org.setName("Tenant " + UUID.randomUUID());
        org.setTimezone("Asia/Kolkata"); // 11:30 at the frozen instant, window open
        org.setStatus(Domain.EntityStatus.ACTIVE);
        organizations.save(org);

        ReminderConfiguration config = new ReminderConfiguration();
        config.setOrganizationId(org.getId());
        config.setAllowedCallingStart(LocalTime.of(9, 0));
        config.setAllowedCallingEnd(LocalTime.of(20, 0));
        config.setMaxCallAttempts(3);
        config.setRetryDelayMinutes(180);
        config.setPreferredChannel(Domain.Channel.VOICE);
        configurations.save(config);

        AppUser agent = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);

        Customer customer = new Customer();
        customer.setOrganizationId(org.getId());
        customer.setAssignedAgentId(agent.getId());
        customer.setCustomerNumber("C-" + UUID.randomUUID().toString().substring(0, 8));
        customer.setFirstName("Asha");
        customer.setLastName("Verma");
        customer.setPhone("+9186" + String.format("%08d",
                Math.floorMod(PHONE_SEQ.incrementAndGet(), 100_000_000L)));
        customer.setStatus(Domain.EntityStatus.ACTIVE);
        customer.setCommunicationConsent(true);
        customers.save(customer);

        Policy policy = new Policy();
        policy.setOrganizationId(org.getId());
        policy.setCustomerId(customer.getId());
        policy.setAgentId(agent.getId());
        policy.setPolicyNumber("P-" + UUID.randomUUID().toString().substring(0, 8));
        policy.setInsuranceProvider("Example Life");
        policy.setPolicyType("TERM");
        policy.setCurrencyCode("INR");
        policy.setPremiumAmount(new BigDecimal("1000.00"));
        policy.setPremiumFrequency(Domain.PremiumFrequency.YEARLY);
        policy.setStatus(Domain.PolicyStatus.ACTIVE);
        policies.save(policy);

        Reminder reminder = new Reminder();
        reminder.setOrganizationId(org.getId());
        reminder.setCustomerId(customer.getId());
        reminder.setPolicyId(policy.getId());
        reminder.setReminderType(Domain.ReminderType.PREMIUM_OVERDUE);
        reminder.setChannel(Domain.Channel.VOICE);
        reminder.setStatus(Domain.ReminderStatus.PENDING);
        reminder.setScheduledAt(FixedClockConfiguration.FIXED_NOW.minusSeconds(60));
        reminder.setIdempotencyKey("voice:" + UUID.randomUUID());
        reminders.save(reminder);

        voice.willReturn(StubVoiceProvider.answered());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    dispatch.dispatch(reminder.getId());
                } catch (Exception ignored) {
                    // A loser that fails outright is fine; what must not happen
                    // is a loser that succeeds in dialling.
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        List<Conversation> calls = conversations.findAll().stream()
                .filter(c -> customer.getId().equals(c.getCustomerId()))
                .toList();

        assertThat(calls)
                .as("the customer was rung once, however many sweeps wanted to")
                .hasSize(1);
        assertThat(voice.callsPlaced()).isEqualTo(1);
        assertThat(reminders.findById(reminder.getId()).orElseThrow().getAttemptCount()).isEqualTo(1);
    }
}
