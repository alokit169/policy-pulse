package com.policypulse.customers;

import com.policypulse.common.Domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Customer as exposed to clients. organizationId is deliberately absent: it is
 * always the caller's own tenant, so returning it adds nothing.
 */
public record CustomerResponse(
        UUID id,
        UUID assignedAgentId,
        String customerNumber,
        String firstName,
        String lastName,
        String fullName,
        String phone,
        String alternatePhone,
        String email,
        LocalDate dateOfBirth,
        String address,
        String preferredLanguage,
        String preferredContactTime,
        boolean communicationConsent,
        boolean optedOut,
        Domain.EntityStatus status,
        String notes,
        Instant createdAt,
        Instant updatedAt) {

    public static CustomerResponse of(Customer c) {
        return new CustomerResponse(
                c.getId(),
                c.getAssignedAgentId(),
                c.getCustomerNumber(),
                c.getFirstName(),
                c.getLastName(),
                c.fullName(),
                c.getPhone(),
                c.getAlternatePhone(),
                c.getEmail(),
                c.getDateOfBirth(),
                c.getAddress(),
                c.getPreferredLanguage(),
                c.getPreferredContactTime(),
                c.isCommunicationConsent(),
                c.isOptedOut(),
                c.getStatus(),
                c.getNotes(),
                c.getCreatedAt(),
                c.getUpdatedAt());
    }
}
