package com.deadlinedrop.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * Helper utilities to extract user identity and role from the current security context authentication.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * Resolves the user UUID from the authentication principal or name.
     */
    public static UUID id(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            String subject = jwt.getSubject();
            return subject != null ? UUID.fromString(subject) : null;
        }
        if (authentication.getName() != null) {
            try {
                return UUID.fromString(authentication.getName());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    /**
     * Resolves the user role name (e.g. STUDENT, TEACHER, ADMIN) from the authentication.
     */
    public static String role(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            String role = jwt.getClaimAsString("role");
            if (role != null && !role.isBlank()) {
                return role;
            }
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String auth = authority.getAuthority();
            if (auth != null && auth.startsWith("ROLE_")) {
                return auth.substring("ROLE_".length());
            }
        }
        return null;
    }
}
