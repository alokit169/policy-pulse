package com.policypulse.dashboard;

import com.policypulse.AbstractIntegrationTest;
import com.policypulse.FixedClockConfiguration;
import com.policypulse.common.Domain;
import com.policypulse.customers.Customer;
import com.policypulse.customers.CustomerRepository;
import com.policypulse.organizations.Organization;
import com.policypulse.policies.Policy;
import com.policypulse.policies.PolicyRepository;
import com.policypulse.premiums.PremiumPayment;
import com.policypulse.premiums.PremiumPaymentRepository;
import com.policypulse.users.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dashboard reads across customers, policies, premiums and reminders at
 * once, so it is the easiest place for a tenant or an agent boundary to leak.
 */
class DashboardTest extends AbstractIntegrationTest {

    private static final AtomicLong PHONE_SEQ = new AtomicLong(System.nanoTime());

    @Autowired private CustomerRepository customers;
    @Autowired private PolicyRepository policies;
    @Autowired private PremiumPaymentRepository premiums;

    /** The tenant's own date, matching what the service computes. */
    private LocalDate today(String timezone) {
        return LocalDate.ofInstant(FixedClockConfiguration.FIXED_NOW, ZoneId.of(timezone));
    }

    private AppUser agentIn(Organization org) {
        return createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);
    }

    private Organization tenantOn(String timezone) {
        Organization org = new Organization();
        org.setName("Tenant " + UUID.randomUUID());
        org.setTimezone(timezone);
        org.setStatus(Domain.EntityStatus.ACTIVE);
        return organizations.save(org);
    }

    private Customer customerOf(AppUser agent) {
        Customer customer = new Customer();
        customer.setOrganizationId(agent.getOrganizationId());
        customer.setAssignedAgentId(agent.getId());
        customer.setCustomerNumber("C-" + UUID.randomUUID().toString().substring(0, 8));
        customer.setFirstName("Asha");
        customer.setLastName("Verma");
        customer.setPhone("+9191" + String.format("%08d", Math.floorMod(PHONE_SEQ.incrementAndGet(), 100_000_000L)));
        customer.setStatus(Domain.EntityStatus.ACTIVE);
        return customers.save(customer);
    }

    private Policy policyOf(AppUser agent, Customer customer) {
        Policy policy = new Policy();
        policy.setOrganizationId(agent.getOrganizationId());
        policy.setCustomerId(customer.getId());
        policy.setAgentId(agent.getId());
        policy.setPolicyNumber("P-" + UUID.randomUUID().toString().substring(0, 8));
        policy.setInsuranceProvider("Example Life");
        policy.setPolicyType("TERM");
        policy.setCurrencyCode("INR");
        policy.setPremiumAmount(new BigDecimal("1000.00"));
        policy.setPremiumFrequency(Domain.PremiumFrequency.YEARLY);
        policy.setStatus(Domain.PolicyStatus.ACTIVE);
        return policies.save(policy);
    }

    private PremiumPayment instalment(Policy policy, LocalDate dueDate, Domain.PremiumStatus status,
                                      String amount, LocalDate paidDate) {
        PremiumPayment instalment = new PremiumPayment();
        instalment.setOrganizationId(policy.getOrganizationId());
        instalment.setPolicyId(policy.getId());
        instalment.setAmount(new BigDecimal(amount));
        instalment.setDueDate(dueDate);
        instalment.setStatus(status);
        instalment.setPaidDate(paidDate);
        return premiums.save(instalment);
    }

    @Test
    void theDashboardRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
    }

    @Test
    void anEmptyTenantReportsZerosRatherThanNulls() throws Exception {
        AppUser agent = createActiveAgent();

        mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(agent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCustomers").value(0))
                .andExpect(jsonPath("$.activePolicies").value(0))
                .andExpect(jsonPath("$.overdue.count").value(0))
                .andExpect(jsonPath("$.overdue.amount").value(0))
                .andExpect(jsonPath("$.collectedThisMonth.amount").value(0))
                .andExpect(jsonPath("$.actionRequired").isEmpty());
    }

    /**
     * Overdue is decided by the due date, not by the stored status. An
     * instalment's status is written when the schedule is generated and is not
     * rewritten as days pass, so a row can still read UPCOMING well after it was
     * due. Trusting the status would under-report what an agency is owed.
     */
    @Test
    void anInstalmentPastItsDateCountsAsOverdueEvenIfItsStatusWasNeverUpdated() throws Exception {
        Organization org = tenantOn("UTC");
        AppUser agent = agentIn(org);
        Policy policy = policyOf(agent, customerOf(agent));

        instalment(policy, today("UTC").minusDays(30), Domain.PremiumStatus.UPCOMING, "1500.00", null);

        mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(agent)))
                .andExpect(jsonPath("$.overdue.count").value(1))
                .andExpect(jsonPath("$.overdue.amount").value(1500.00))
                .andExpect(jsonPath("$.actionRequired[0].daysOverdue").value(30));
    }

    @Test
    void settledInstalmentsAreNeverOverdue() throws Exception {
        Organization org = tenantOn("UTC");
        AppUser agent = agentIn(org);
        Policy policy = policyOf(agent, customerOf(agent));

        instalment(policy, today("UTC").minusDays(10), Domain.PremiumStatus.PAID, "500.00", today("UTC").minusDays(9));
        instalment(policy, today("UTC").minusDays(5), Domain.PremiumStatus.WAIVED, "500.00", null);

        mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(agent)))
                .andExpect(jsonPath("$.overdue.count").value(0))
                .andExpect(jsonPath("$.actionRequired").isEmpty());
    }

    @Test
    void moneyTotalsKeepTheirExactValue() throws Exception {
        Organization org = tenantOn("UTC");
        AppUser agent = agentIn(org);
        Policy policy = policyOf(agent, customerOf(agent));

        instalment(policy, today("UTC").minusDays(3), Domain.PremiumStatus.OVERDUE, "1234.56", null);
        instalment(policy, today("UTC").minusDays(2), Domain.PremiumStatus.OVERDUE, "0.44", null);

        mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(agent)))
                .andExpect(jsonPath("$.overdue.count").value(2))
                .andExpect(jsonPath("$.overdue.amount").value(1235.00));
    }

    @Test
    void premiumsFallingDueSoonAreCountedSeparatelyFromOverdueOnes() throws Exception {
        Organization org = tenantOn("UTC");
        AppUser agent = agentIn(org);
        Policy policy = policyOf(agent, customerOf(agent));

        instalment(policy, today("UTC").plusDays(3), Domain.PremiumStatus.UPCOMING, "100.00", null);
        instalment(policy, today("UTC").plusDays(7), Domain.PremiumStatus.UPCOMING, "200.00", null);
        // Beyond the window.
        instalment(policy, today("UTC").plusDays(30), Domain.PremiumStatus.UPCOMING, "999.00", null);

        mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(agent)))
                .andExpect(jsonPath("$.dueNextSevenDays.count").value(2))
                .andExpect(jsonPath("$.dueNextSevenDays.amount").value(300.00))
                .andExpect(jsonPath("$.overdue.count").value(0));
    }

    @Test
    void collectedThisMonthCountsWhatWasPaidInsideTheMonth() throws Exception {
        Organization org = tenantOn("UTC");
        AppUser agent = agentIn(org);
        Policy policy = policyOf(agent, customerOf(agent));
        LocalDate today = today("UTC");

        instalment(policy, today.minusDays(10), Domain.PremiumStatus.PAID, "750.00", today.withDayOfMonth(2));
        instalment(policy, today.minusDays(9), Domain.PremiumStatus.PAID, "250.00", today);
        // Paid last month, so outside this month's figure.
        instalment(policy, today.minusMonths(2), Domain.PremiumStatus.PAID, "5000.00",
                today.withDayOfMonth(1).minusDays(3));

        mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(agent)))
                .andExpect(jsonPath("$.collectedThisMonth.count").value(2))
                .andExpect(jsonPath("$.collectedThisMonth.amount").value(1000.00));
    }

    /** An agent's dashboard must not include a colleague's book. */
    @Test
    void anAgentSeesOnlyTheirOwnFigures() throws Exception {
        Organization org = tenantOn("UTC");
        AppUser mine = agentIn(org);
        AppUser colleague = agentIn(org);

        instalment(policyOf(mine, customerOf(mine)), today("UTC").minusDays(1),
                Domain.PremiumStatus.OVERDUE, "100.00", null);
        instalment(policyOf(colleague, customerOf(colleague)), today("UTC").minusDays(1),
                Domain.PremiumStatus.OVERDUE, "900.00", null);

        mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(mine)))
                .andExpect(jsonPath("$.scope").value("OWN_BOOK"))
                .andExpect(jsonPath("$.activeCustomers").value(1))
                .andExpect(jsonPath("$.overdue.count").value(1))
                .andExpect(jsonPath("$.overdue.amount").value(100.00));
    }

    @Test
    void aManagerSeesTheWholeOrganization() throws Exception {
        Organization org = tenantOn("UTC");
        AppUser agentOne = agentIn(org);
        AppUser agentTwo = agentIn(org);
        AppUser manager = createUserIn(org.getId(), Domain.Role.MANAGER, Domain.EntityStatus.ACTIVE);

        instalment(policyOf(agentOne, customerOf(agentOne)), today("UTC").minusDays(1),
                Domain.PremiumStatus.OVERDUE, "100.00", null);
        instalment(policyOf(agentTwo, customerOf(agentTwo)), today("UTC").minusDays(1),
                Domain.PremiumStatus.OVERDUE, "900.00", null);

        mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(manager)))
                .andExpect(jsonPath("$.scope").value("ORGANIZATION"))
                .andExpect(jsonPath("$.activeCustomers").value(2))
                .andExpect(jsonPath("$.activePolicies").value(2))
                .andExpect(jsonPath("$.overdue.count").value(2))
                .andExpect(jsonPath("$.overdue.amount").value(1000.00));
    }

    @Test
    void anotherTenantsFiguresNeverAppear() throws Exception {
        Organization mine = tenantOn("UTC");
        AppUser manager = createUserIn(mine.getId(), Domain.Role.MANAGER, Domain.EntityStatus.ACTIVE);

        Organization theirs = tenantOn("UTC");
        AppUser outsider = agentIn(theirs);
        instalment(policyOf(outsider, customerOf(outsider)), today("UTC").minusDays(1),
                Domain.PremiumStatus.OVERDUE, "9999.00", null);

        mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(manager)))
                .andExpect(jsonPath("$.activeCustomers").value(0))
                .andExpect(jsonPath("$.overdue.count").value(0))
                .andExpect(jsonPath("$.actionRequired").isEmpty());
    }

    @Test
    void outstandingWorkIsListedOldestFirstAndCapped() throws Exception {
        Organization org = tenantOn("UTC");
        AppUser agent = agentIn(org);
        Policy policy = policyOf(agent, customerOf(agent));

        for (int daysAgo = 1; daysAgo <= 12; daysAgo++) {
            instalment(policy, today("UTC").minusDays(daysAgo), Domain.PremiumStatus.OVERDUE, "10.00", null);
        }

        mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(agent)))
                .andExpect(jsonPath("$.overdue.count").value(12))
                .andExpect(jsonPath("$.actionRequired.length()").value(10))
                // Oldest first: twelve days overdue leads.
                .andExpect(jsonPath("$.actionRequired[0].daysOverdue").value(12))
                .andExpect(jsonPath("$.actionRequired[0].customerName").value("Asha Verma"))
                .andExpect(jsonPath("$.actionRequired[0].policyNumber").value(policy.getPolicyNumber()));
    }

    /** Overdue is a date comparison, so it has to be the tenant's date. */
    @Test
    void theTenantsOwnDateDecidesWhatIsOverdue() throws Exception {
        String behind = "Pacific/Midway";
        Organization org = tenantOn(behind);
        AppUser agent = agentIn(org);
        Policy policy = policyOf(agent, customerOf(agent));

        // Yesterday in UTC, but still today where this tenant is, so not yet overdue.
        instalment(policy, today("UTC"), Domain.PremiumStatus.UPCOMING, "100.00", null);

        mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(agent)))
                .andExpect(jsonPath("$.timezone").value(behind))
                .andExpect(jsonPath("$.asOf").value(today(behind).toString()))
                .andExpect(jsonPath("$.overdue.count").value(0));
    }
}
