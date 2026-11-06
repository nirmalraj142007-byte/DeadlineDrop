package com.deadlinedrop.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(max = 128) String password
    ) {}

    public record TokenResponse(
            String accessToken,
            Instant expiresAt,
            String role,
            String displayName
    ) {}

    public record MeResponse(
            UUID id,
            String email,
            String displayName,
            String role
    ) {}
}
