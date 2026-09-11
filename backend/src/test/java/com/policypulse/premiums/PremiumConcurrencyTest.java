package com.policypulse.premiums;

import com.policypulse.AbstractIntegrationTest;
import com.policypulse.audit.AuditLog;
import com.policypulse.audit.AuditLogRepository;
import com.policypulse.common.Domain;
import com.policypulse.customers.Customer;
import com.policypulse.customers.CustomerRepository;
import com.policypulse.policies.Policy;
import com.policypulse.policies.PolicyRepository;
import com.policypulse.security.AuthUser;
import com.policypulse.users.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recording a payment reads an instalment, checks it is not already settled, and
 * then writes. Under concurrency that check and write can interleave, so the
 * invariant is enforced here rather than assumed.
 */
class PremiumConcurrencyTest extends AbstractIntegrationTest {

    private static final int THREADS = 8;

    @Autowired private PremiumService premiumService;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private PremiumPaymentRepository premiums;
    @Autowired private PolicyRepository policies;
    @Autowired private CustomerRepository customers;

    /** SecurityContextHolder is thread local, so each worker sets its own. */
    private void authenticateAs(AppUser user) {
        AuthUser principal = new AuthUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private Policy givenAPolicyWithOneInstalment(AppUser agent) {
        Customer customer = new Customer();
        customer.setOrganizationId(agent.getOrganizationId());
        customer.setAssignedAgentId(agent.getId());
        customer.setCustomerNumber("C-" + UUID.randomUUID().toString().substring(0, 8));
        customer.setFirstName("Race");
        customer.setLastName("Condition");
        customer.setPhone("+9195" + String.format("%08d", Math.floorMod(System.nanoTime(), 100_000_000L)));
        customer.setStatus(Domain.EntityStatus.ACTIVE);
        customers.save(customer);

        Policy policy = new Policy();
        policy.setOrganizationId(agent.getOrganizationId());
        policy.setCustomerId(customer.getId());
        policy.setAgentId(agent.getId());
        policy.setPolicyNumber("P-" + UUID.randomUUID().toString().substring(0, 8));
        policy.setInsuranceProvider("Example Life");
        policy.setPolicyType("TERM");
        policy.setPremiumAmount(new BigDecimal("1000.00"));
        policy.setPremiumFrequency(Domain.PremiumFrequency.YEARLY);
        policy.setStatus(Domain.PolicyStatus.ACTIVE);
        policies.save(policy);

        PremiumPayment instalment = new PremiumPayment();
        instalment.setOrganizationId(agent.getOrganizationId());
        instalment.setPolicyId(policy.getId());
        instalment.setAmount(new BigDecimal("1000.00"));
        instalment.setDueDate(LocalDate.of(2024, 1, 1));
        instalment.setStatus(Domain.PremiumStatus.OVERDUE);
        premiums.save(instalment);

        return policy;
    }

    @Test
    void onlyOneOfManySimultaneousPaymentsIsRecorded() throws Exception {
        AppUser agent = createActiveAgent();
        Policy policy = givenAPolicyWithOneInstalment(agent);
        UUID premiumId = premiums.findByPolicyIdOrderByDueDateAsc(policy.getId()).get(0).getId();

        // Every worker blocks here so they hit the service together rather than
        // one after another, which is what makes the interleaving possible.
        CountDownLatch releaseAll = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<String>> attempts = new ArrayList<>();

        for (int i = 0; i < THREADS; i++) {
            String reference = "REF-" + i;
            attempts.add(pool.submit(() -> {
                authenticateAs(agent);
                releaseAll.await();
                try {
                    premiumService.recordPayment(policy.getId(), premiumId,
                            new RecordPaymentRequest(null, reference, null));
                    return "recorded:" + reference;
                } catch (Exception ex) {
                    return "rejected:" + ex.getClass().getSimpleName();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
        }

        releaseAll.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<String> outcomes = new ArrayList<>();
        for (Future<String> attempt : attempts) {
            outcomes.add(attempt.get());
        }

        List<String> recorded = outcomes.stream().filter(o -> o.startsWith("recorded:")).toList();
        assertThat(recorded)
                .as("exactly one payment may be recorded, outcomes were %s", outcomes)
                .hasSize(1);

        // The stored row must match the one caller that was told it succeeded.
        PremiumPayment settled = premiums.findById(premiumId).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(Domain.PremiumStatus.PAID);
        assertThat(recorded.get(0)).isEqualTo("recorded:" + settled.getPaymentReference());

        // Audit rows are written in their own transaction, so a losing attempt
        // could otherwise leave a record of a collection that never happened.
        List<AuditLog> paidEntries = auditLogs
                .findByActorEmailIgnoreCaseOrderByTimestampDesc(agent.getEmail()).stream()
                .filter(entry -> "PREMIUM_RECORDED_PAID".equals(entry.getAction()))
                .toList();
        assertThat(paidEntries)
                .as("one collection must leave exactly one audit entry")
                .hasSize(1);
    }
}
