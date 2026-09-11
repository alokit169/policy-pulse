package com.policypulse.customers;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Create and update payload. Fields the caller must not set directly, such as
 * the organization, are taken from the authenticated user instead.
 */
public record CustomerRequest(
        @NotBlank(message = "First name is required")
        @Size(max = 120, message = "First name must be at most 120 characters")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 120, message = "Last name must be at most 120 characters")
        String lastName,

        @NotBlank(message = "Phone is required")
        @Size(max = 40, message = "Phone must be at most 40 characters")
        @Pattern(regexp = "^[+0-9][0-9 ()-]{5,}$", message = "Phone must be a valid number")
        String phone,

        @Size(max = 40, message = "Alternate phone must be at most 40 characters")
        String alternatePhone,

        @Email(message = "Email must be valid")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String email,

        @Past(message = "Date of birth must be in the past")
        LocalDate dateOfBirth,

        @Size(max = 500, message = "Address must be at most 500 characters")
        String address,

        @Size(max = 32, message = "Preferred language must be at most 32 characters")
        String preferredLanguage,

        @Size(max = 64, message = "Preferred contact time must be at most 64 characters")
        String preferredContactTime,

        /** Defaults to true on create when omitted. */
        Boolean communicationConsent,

        String notes,

        /**
         * Optional. Agents may only assign customers to themselves, so this is
         * ignored for them. Managers and admins may assign to any agent in their
         * own organization.
         */
        UUID assignedAgentId,

        /**
         * Optional. Generated when absent, which is the normal case.
         */
        @Size(max = 64, message = "Customer number must be at most 64 characters")
        String customerNumber) {
}
