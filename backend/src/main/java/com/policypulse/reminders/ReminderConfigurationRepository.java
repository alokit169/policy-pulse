package com.policypulse.reminders;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ReminderConfigurationRepository extends JpaRepository<ReminderConfiguration, UUID> {
    Optional<ReminderConfiguration> findByOrganizationId(UUID organizationId);
}
