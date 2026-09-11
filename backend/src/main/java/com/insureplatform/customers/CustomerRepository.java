package com.insureplatform.customers;

import com.insureplatform.common.Domain;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    Optional<Customer> findByIdAndOrganizationId(UUID id, UUID orgId);

    @Query("""
            SELECT c FROM Customer c WHERE c.organizationId = :org
            AND (:agentId IS NULL OR c.assignedAgentId = :agentId)
            AND (:status IS NULL OR c.status = :status)
            AND (:q IS NULL OR LOWER(c.firstName) LIKE LOWER(CONCAT('%',:q,'%'))
                 OR LOWER(c.lastName) LIKE LOWER(CONCAT('%',:q,'%'))
                 OR c.phone LIKE CONCAT('%',:q,'%')
                 OR LOWER(c.email) LIKE LOWER(CONCAT('%',:q,'%'))
                 OR LOWER(c.customerNumber) LIKE LOWER(CONCAT('%',:q,'%')))
            """)
    Page<Customer> search(@Param("org") UUID org, @Param("agentId") UUID agentId,
                          @Param("status") Domain.EntityStatus status, @Param("q") String q, Pageable pageable);
}
