package com.insureplatform.users;

import com.insureplatform.common.Domain;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByEmailIgnoreCase(String email);
    List<AppUser> findByOrganizationId(UUID organizationId);
    List<AppUser> findByOrganizationIdAndRole(UUID organizationId, Domain.Role role);
    List<AppUser> findByManagerId(UUID managerId);
}
