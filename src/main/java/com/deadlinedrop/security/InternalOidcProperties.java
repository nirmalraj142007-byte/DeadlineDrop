package com.deadlinedrop.security;

import jakarta.validation.constraints.AssertTrue;

/**
 * Who may call {@code /internal/**} in the cloud: Cloud Scheduler presenting a Google-signed OIDC
 * token minted for {@code audience} (env {@code INTERNAL_AUDIENCE}, the service URL) on behalf of
 * {@code schedulerEmail} (env {@code SCHEDULER_SA_EMAIL}).
 *
 * <p>Both blank means this service has no scheduler (arms A and B): the internal chain denies all.
 * Exactly one set is a deployment mistake and stops the app at startup.
 */
public class InternalOidcProperties {

    private String audience = "";
    private String schedulerEmail = "";

    public boolean isConfigured() {
        return !isBlank(audience) && !isBlank(schedulerEmail);
    }

    @AssertTrue(message = "INTERNAL_AUDIENCE and SCHEDULER_SA_EMAIL must be set together (both or neither)")
    public boolean isBothOrNeither() {
        return isBlank(audience) == isBlank(schedulerEmail);
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience == null ? "" : audience.strip();
    }

    public String getSchedulerEmail() {
        return schedulerEmail;
    }

    public void setSchedulerEmail(String schedulerEmail) {
        this.schedulerEmail = schedulerEmail == null ? "" : schedulerEmail.strip();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
