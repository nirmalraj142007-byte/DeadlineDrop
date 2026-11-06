package com.deadlinedrop.service;

import com.deadlinedrop.api.ApiException;
import com.deadlinedrop.api.ErrorCodes;
import com.deadlinedrop.api.dto.AuthDtos.LoginRequest;
import com.deadlinedrop.api.dto.AuthDtos.MeResponse;
import com.deadlinedrop.api.dto.AuthDtos.TokenResponse;
import com.deadlinedrop.domain.User;
import com.deadlinedrop.repository.UserRepository;
import com.deadlinedrop.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AuthService {

    // Pre-computed BCrypt hash (cost 10) for "dummy-password-timing-defense" to guarantee constant-time execution
    // when email is absent, preventing user enumeration via timing analysis.
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5FzKqYtG8pM0nU6R1lqQ7X6ZkO7uW";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.jwtService = Objects.requireNonNull(jwtService, "jwtService must not be null");
    }

    public TokenResponse login(LoginRequest request) {
        Optional<User> userOptional = userRepository.findByEmailIgnoreCase(request.email());
        if (userOptional.isEmpty()) {
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            throw ApiException.unauthorized(ErrorCodes.INVALID_CREDENTIALS, "Wrong email or password");
        }

        User user = userOptional.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized(ErrorCodes.INVALID_CREDENTIALS, "Wrong email or password");
        }

        return jwtService.issue(user);
    }

    public MeResponse getMe(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "User not found"));
        return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole().name());
    }
}
