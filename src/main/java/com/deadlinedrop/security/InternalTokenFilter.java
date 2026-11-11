package com.deadlinedrop.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * Local and test only: a request whose {@code X-Internal-Token} equals the configured token is
 * authenticated as the scheduler with ROLE_INTERNAL. Anything else passes through unauthenticated
 * and the chain answers 401. Not a Spring bean on purpose: as a bean, Boot would also register it
 * on every servlet path.
 */
final class InternalTokenFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Internal-Token";
    static final String PRINCIPAL = "internal-scheduler";

    private final byte[] expected;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    InternalTokenFilter(String token) {
        this.expected = Objects.requireNonNull(token, "token must not be null").getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        // MessageDigest.isEqual takes the same time wherever the first difference is, so response
        // timing does not reveal how much of a guessed token was right.
        if (presented != null && MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
            SecurityContext context = contexts.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    PRINCIPAL, null, AuthorityUtils.createAuthorityList("ROLE_INTERNAL")));
            contexts.setContext(context);
        }
        chain.doFilter(request, response);
    }
}
