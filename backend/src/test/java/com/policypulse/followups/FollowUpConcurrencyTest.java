package com.policypulse.followups;

import com.policypulse.AbstractIntegrationTest;
import com.policypulse.common.Domain;
import com.policypulse.customers.Customer;
import com.policypulse.customers.CustomerRepository;
import com.policypulse.notifications.InAppNotificationRepository;
import com.policypulse.organizations.Organization;
import com.policypulse.policies.Policy;
import com.policypulse.policies.PolicyRepository;
import com.policypulse.premiums.PremiumPayment;
import com.policypulse.premiums.PremiumPaymentRepository;
import com.policypulse.tasks.HumanTaskRepository;
import com.policypulse.users.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hourly sweep and a manager pressing "work through them now" can want the
 * same follow-up at the same instant. Deciding from a status read a moment
 * earlier lets both through, and the agent is told twice about one promise and
 * finds two identical tasks in their queue.
 *
 * <p>The third time this shape has appeared here, after simultaneous premium
 * payments and two sweeps dispatching one reminder. Whichever check decides may
 * only be made where the row lock is.
 */
class FollowUpConcurrencyTest extends AbstractIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 9, 14);
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 15);
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    @Autowired private FollowUpRunner runner;
    @Autowired private FollowUpRepository followUps;
    @Autowired private CustomerRepository customers;
    @Autowired private PolicyRepository policies;
    @Autowired private PremiumPaymentRepository premiums;
    @Autowired private HumanTaskRepository humanTasks;
    @Autowired private InAppNotificationRepository notifications;

    private record Scene(Organization org, AppUser agent, Customer customer, Policy policy) {
    }

    private Scene given() {
        Organization org = new Organization();
        org.setName("Tenant " + UUID.randomUUID());
        org.setTimezone(ZONE.getId());
        org.setStatus(Domain.EntityStatus.ACTIVE);
        organizations.save(org);

        AppUser agent = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);

        Customer customer = new Customer();
        customer.setOrganizationId(org.getId());
        customer.setAssignedAgentId(agent.getId());
        customer.setCustomerNumber("C-" + UUID.randomUUID().toString().substring(0, 8));
        customer.setFirstName("Meera");
        customer.setLastName("Nair");
        customer.setPhone("+9187" + String.format("%08d", Math.floorMod(SEQ.incrementAndGet(), 100_000_000L)));
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
        policy.setPremiumAmount(new BigDecimal("4500.00"));
        policy.setPremiumFrequency(Domain.PremiumFrequency.YEARLY);
        policy.setStatus(Domain.PolicyStatus.ACTIVE);
        policies.save(policy);

        return new Scene(org, agent, customer, policy);
    }

    private FollowUp commitment(Scene scene, LocalDate promisedFor) {
        FollowUp followUp = new FollowUp();
        followUp.setOrganizationId(scene.org().getId());
        followUp.setCustomerId(scene.customer().getId());
        followUp.setPolicyId(scene.policy().getId());
        followUp.setAssignedAgentId(scene.agent().getId());
        followUp.setReason(FollowUpReason.PAYMENT_COMMITMENT.name());
        followUp.setCommitmentDate(promisedFor);
        followUp.setDueAt(ZonedDateTime.of(promisedFor, LocalTime.of(9, 0), ZONE).toInstant());
        followUp.setStatus(Domain.FollowUpStatus.OPEN);
        return followUps.save(followUp);
    }

    @Test
    void twoSweepsAtOnceTellTheAgentOnce() throws Exception {
        Scene scene = given();
        PremiumPayment owed = new PremiumPayment();
        owed.setOrganizationId(scene.org().getId());
        owed.setPolicyId(scene.policy().getId());
        owed.setAmount(new BigDecimal("4500.00"));
        owed.setDueDate(YESTERDAY);
        owed.setStatus(Domain.PremiumStatus.OVERDUE);
        premiums.save(owed);

        commitment(scene, YESTERDAY);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    runner.runFor(scene.org().getId());
                } catch (Exception ignored) {
                    // A loser that fails outright is fine.
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        long told = notifications.findByUserIdOrderByCreatedAtDesc(
                scene.agent().getId(), PageRequest.of(0, 50)).getTotalElements();
        long tasks = humanTasks.findByOrganizationIdAndStatusOrderByCreatedAtDesc(
                scene.org().getId(), Domain.HumanTaskStatus.OPEN, PageRequest.of(0, 50)).getTotalElements();

        assertThat(told).as("told once, however many sweeps wanted to").isEqualTo(1);
        assertThat(tasks).isEqualTo(1);
    }

}
