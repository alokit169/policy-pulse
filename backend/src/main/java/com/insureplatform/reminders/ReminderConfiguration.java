package com.insureplatform.reminders;

import com.insureplatform.common.Domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "reminder_configurations")
public class ReminderConfiguration {
    @Id
    private UUID id;
    private UUID organizationId;
    private String daysBeforeDue;
    private String daysAfterDue;
    private int maxCallAttempts;
    private int retryDelayMinutes;
    private LocalTime allowedCallingStart;
    private LocalTime allowedCallingEnd;
    @Enumerated(EnumType.STRING)
    private Domain.Channel preferredChannel;

    @PrePersist
    void pre() {
        if (id == null) id = UUID.randomUUID();
        if (daysBeforeDue == null) daysBeforeDue = "10,5,1,0";
        if (daysAfterDue == null) daysAfterDue = "2";
        if (maxCallAttempts == 0) maxCallAttempts = 3;
        if (retryDelayMinutes == 0) retryDelayMinutes = 180;
        if (allowedCallingStart == null) allowedCallingStart = LocalTime.of(9, 0);
        if (allowedCallingEnd == null) allowedCallingEnd = LocalTime.of(20, 0);
        if (preferredChannel == null) preferredChannel = Domain.Channel.IN_APP;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public String getDaysBeforeDue() { return daysBeforeDue; }
    public void setDaysBeforeDue(String daysBeforeDue) { this.daysBeforeDue = daysBeforeDue; }
    public String getDaysAfterDue() { return daysAfterDue; }
    public void setDaysAfterDue(String daysAfterDue) { this.daysAfterDue = daysAfterDue; }
    public int getMaxCallAttempts() { return maxCallAttempts; }
    public void setMaxCallAttempts(int maxCallAttempts) { this.maxCallAttempts = maxCallAttempts; }
    public int getRetryDelayMinutes() { return retryDelayMinutes; }
    public void setRetryDelayMinutes(int retryDelayMinutes) { this.retryDelayMinutes = retryDelayMinutes; }
    public LocalTime getAllowedCallingStart() { return allowedCallingStart; }
    public void setAllowedCallingStart(LocalTime allowedCallingStart) { this.allowedCallingStart = allowedCallingStart; }
    public LocalTime getAllowedCallingEnd() { return allowedCallingEnd; }
    public void setAllowedCallingEnd(LocalTime allowedCallingEnd) { this.allowedCallingEnd = allowedCallingEnd; }
    public Domain.Channel getPreferredChannel() { return preferredChannel; }
    public void setPreferredChannel(Domain.Channel preferredChannel) { this.preferredChannel = preferredChannel; }
}
