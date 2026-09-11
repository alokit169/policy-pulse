package com.insureplatform.customers;

import com.insureplatform.common.Domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class Customer {
    @Id
    private UUID id;
    private UUID organizationId;
    private UUID assignedAgentId;
    private String customerNumber;
    private String firstName;
    private String lastName;
    private String phone;
    private String alternatePhone;
    private String email;
    private LocalDate dateOfBirth;
    private String address;
    private String preferredLanguage;
    private String preferredContactTime;
    private boolean communicationConsent = true;
    private boolean optedOut;
    @Enumerated(EnumType.STRING)
    private Domain.EntityStatus status;
    @Column(columnDefinition = "TEXT")
    private String notes;
    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void pre() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = Domain.EntityStatus.ACTIVE;
        if (preferredLanguage == null) preferredLanguage = "en";
    }

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    public String fullName() { return firstName + " " + lastName; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public UUID getAssignedAgentId() { return assignedAgentId; }
    public void setAssignedAgentId(UUID assignedAgentId) { this.assignedAgentId = assignedAgentId; }
    public String getCustomerNumber() { return customerNumber; }
    public void setCustomerNumber(String customerNumber) { this.customerNumber = customerNumber; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getAlternatePhone() { return alternatePhone; }
    public void setAlternatePhone(String alternatePhone) { this.alternatePhone = alternatePhone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getPreferredLanguage() { return preferredLanguage; }
    public void setPreferredLanguage(String preferredLanguage) { this.preferredLanguage = preferredLanguage; }
    public String getPreferredContactTime() { return preferredContactTime; }
    public void setPreferredContactTime(String preferredContactTime) { this.preferredContactTime = preferredContactTime; }
    public boolean isCommunicationConsent() { return communicationConsent; }
    public void setCommunicationConsent(boolean communicationConsent) { this.communicationConsent = communicationConsent; }
    public boolean isOptedOut() { return optedOut; }
    public void setOptedOut(boolean optedOut) { this.optedOut = optedOut; }
    public Domain.EntityStatus getStatus() { return status; }
    public void setStatus(Domain.EntityStatus status) { this.status = status; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
