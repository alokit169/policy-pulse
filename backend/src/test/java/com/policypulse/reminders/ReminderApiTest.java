package com.policypulse.reminders;

import com.policypulse.AbstractIntegrationTest;
import com.policypulse.common.Domain;
import com.policypulse.notifications.InAppNotificationRepository;
import com.policypulse.notifications.NotificationService;
import com.policypulse.organizations.Organization;
import com.policypulse.users.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReminderApiTest extends AbstractIntegrationTest {

    @Autowired private NotificationService notificationService;
    @Autowired private InAppNotificationRepository notifications;

    private Map<String, Object> validConfiguration() {
        Map<String, Object> body = new HashMap<>();
        body.put("daysBeforeDue", "7,3,0");
        body.put("daysAfterDue", "1,5");
        body.put("maxCallAttempts", 3);
        body.put("retryDelayMinutes", 120);
        body.put("allowedCallingStart", "09:00:00");
        body.put("allowedCallingEnd", "18:00:00");
        body.put("preferredChannel", "IN_APP");
        return body;
    }

    @Test
    void reminderRoutesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/reminders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reminders/configuration")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    void aTenantWithoutSettingsIsGivenTheDefaults() throws Exception {
        AppUser agent = createActiveAgent();

        mvc.perform(get("/api/reminders/configuration")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(agent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daysBeforeDue").value("10,5,1,0"))
                .andExpect(jsonPath("$.daysAfterDue").value("2"))
                .andExpect(jsonPath("$.preferredChannel").value("IN_APP"))
                .andExpect(jsonPath("$.timezone").isNotEmpty());
    }

    @Test
    void aManagerMayChangeTheSettingsAndAnAgentMayNot() throws Exception {
        Organization org = createOrganization();
        AppUser manager = createUserIn(org.getId(), Domain.Role.MANAGER, Domain.EntityStatus.ACTIVE);
        AppUser agent = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);

        mvc.perform(put("/api/reminders/configuration")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validConfiguration())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daysBeforeDue").value("7,3,0"))
                .andExpect(jsonPath("$.retryDelayMinutes").value(120));

        // An agent is authenticated and allowed here, so this is 403 rather than
        // the 404 used when a record must not be revealed at all.
        mvc.perform(put("/api/reminders/configuration")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(agent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validConfiguration())))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonsensicalSettingsAreRejected() throws Exception {
        AppUser manager = createUser(Domain.Role.ORGANIZATION_ADMIN, Domain.EntityStatus.ACTIVE);
        String token = tokenFor(manager);

        Map<String, Object> badDays = validConfiguration();
        badDays.put("daysBeforeDue", "ten,five");
        mvc.perform(put("/api/reminders/configuration")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badDays)))
                .andExpect(status().isBadRequest());

        Map<String, Object> backwardsWindow = validConfiguration();
        backwardsWindow.put("allowedCallingStart", "20:00:00");
        backwardsWindow.put("allowedCallingEnd", "09:00:00");
        mvc.perform(put("/api/reminders/configuration")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(backwardsWindow)))
                .andExpect(status().isBadRequest());

        Map<String, Object> tooManyAttempts = validConfiguration();
        tooManyAttempts.put("maxCallAttempts", 99);
        mvc.perform(put("/api/reminders/configuration")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tooManyAttempts)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyAManagerMayRunDetectionOnDemand() throws Exception {
        Organization org = createOrganization();
        AppUser manager = createUserIn(org.getId(), Domain.Role.MANAGER, Domain.EntityStatus.ACTIVE);
        AppUser agent = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);

        mvc.perform(post("/api/reminders/detect")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").exists());

        mvc.perform(post("/api/reminders/detect")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(agent)))
                .andExpect(status().isForbidden());
    }

    @Test
    void notificationsBelongToOneUserOnly() throws Exception {
        Organization org = createOrganization();
        AppUser mine = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);
        AppUser colleague = createUserIn(org.getId(), Domain.Role.AGENT, Domain.EntityStatus.ACTIVE);

        notificationService.notifyUser(org.getId(), mine.getId(), "For me", "body");
        notificationService.notifyUser(org.getId(), colleague.getId(), "For them", "body");

        mvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(mine)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].title").value("For me"));

        // A colleague's notification is reported as missing, not forbidden.
        var theirs = notifications
                .findByUserIdOrderByCreatedAtDesc(colleague.getId(), PageRequest.of(0, 1))
                .getContent().get(0);

        mvc.perform(post("/api/notifications/" + theirs.getId() + "/read")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(mine)))
                .andExpect(status().isNotFound());
    }

    @Test
    void markingNotificationsReadUpdatesTheUnreadCount() throws Exception {
        AppUser user = createActiveAgent();
        String token = tokenFor(user);

        notificationService.notifyUser(user.getOrganizationId(), user.getId(), "One", "body");
        notificationService.notifyUser(user.getOrganizationId(), user.getId(), "Two", "body");

        mvc.perform(get("/api/notifications/unread-count").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.unread").value(2));

        mvc.perform(get("/api/notifications").param("unreadOnly", "true")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.total").value(2));

        mvc.perform(post("/api/notifications/read-all").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2));

        mvc.perform(get("/api/notifications/unread-count").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.unread").value(0));

        // Read ones are still listed, just no longer unread.
        mvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].read").value(true));
    }

    @Test
    void remindersAreListedPerTenant() throws Exception {
        AppUser mine = createActiveAgent();
        AppUser other = createActiveAgent();

        mvc.perform(get("/api/reminders").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(mine)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mvc.perform(get("/api/reminders").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }
}
