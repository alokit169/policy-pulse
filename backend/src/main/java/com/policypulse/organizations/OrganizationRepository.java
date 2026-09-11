package com.policypulse.organizations;

import com.policypulse.common.Domain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    /**
     * The tenants a sweep should run for. Asked of the database rather than
     * loaded and filtered, since every background job starts with this and only
     * ever wants the identifiers.
     */
    @Query("SELECT o.id FROM Organization o WHERE o.status = :status ORDER BY o.createdAt ASC")
    List<UUID> findIdsByStatus(@Param("status") Domain.EntityStatus status);
}
