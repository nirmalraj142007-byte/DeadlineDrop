package com.deadlinedrop.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The shared secret the local scheduler sends in {@code X-Internal-Token} ({@code dd.internal.token},
 * from env {@code INTERNAL_TOKEN}). Bound only under the local and test profiles by
 * {@link InternalSecurityConfig}, so the cloud never needs it; there a missing or short token stops
 * the app at startup instead of leaving the endpoints open.
 */
public class InternalTokenProperties {

    @NotBlank(message = "INTERNAL_TOKEN must be set (generate one with: openssl rand -base64 32)")
    @Size(min = 32, message = "INTERNAL_TOKEN must be at least 32 characters")
    private String token;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
