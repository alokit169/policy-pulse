package com.policypulse.customers;

import com.policypulse.common.Domain;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    Optional<Customer> findByIdAndOrganizationId(UUID id, UUID orgId);

    Optional<Customer> findByOrganizationIdAndPhone(UUID organizationId, String phone);

    boolean existsByOrganizationIdAndCustomerNumberIgnoreCase(UUID organizationId, String customerNumber);

    /**
     * {@code pattern} is always a LIKE pattern and never null: "%" matches
     * everything. Passing a nullable term into CONCAT left the parameter
     * untyped, and Postgres then rejected the query with
     * "function lower(bytea) does not exist".
     *
     * <p>firstName is NOT NULL, so an unfiltered search still matches every row.
     * The caller escapes LIKE metacharacters in the search term, so a customer
     * searching for a literal "%" does not match everything.
     */
    @Query("""
            SELECT c FROM Customer c WHERE c.organizationId = :org
            AND (:agentId IS NULL OR c.assignedAgentId = :agentId)
            AND (:status IS NULL OR c.status = :status)
            AND (LOWER(c.firstName) LIKE :pattern ESCAPE '!'
                 OR LOWER(c.lastName) LIKE :pattern ESCAPE '!'
                 OR LOWER(c.phone) LIKE :pattern ESCAPE '!'
                 OR LOWER(c.email) LIKE :pattern ESCAPE '!'
                 OR LOWER(c.customerNumber) LIKE :pattern ESCAPE '!')
            """)
    Page<Customer> search(@Param("org") UUID org, @Param("agentId") UUID agentId,
                          @Param("status") Domain.EntityStatus status,
                          @Param("pattern") String pattern, Pageable pageable);
}
