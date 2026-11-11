package com.deadlinedrop.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;

/**
 * Takes the authoritative arrival stamp for commit requests.
 *
 * <p>This is the clock that decides fairness: the application's injected {@link Clock} read at
 * request arrival, before security, request-id handling, body parsing or any database work. It is
 * deliberately not the database's {@code now()}, which is stored separately as
 * {@code db_committed_at} for audit only.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CommitTimestampFilter extends OncePerRequestFilter {

    /** Request attribute holding the {@link java.time.Instant} at which the commit request arrived. */
    public static final String ARRIVED_AT = CommitTimestampFilter.class.getName() + ".ARRIVED_AT";

    private static final String COMMIT_PATH = "/api/assignments/*/commits";

    private final Clock clock;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public CommitTimestampFilter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (HttpMethod.POST.matches(request.getMethod()) && pathMatcher.match(COMMIT_PATH, request.getRequestURI())) {
            request.setAttribute(ARRIVED_AT, clock.instant());
        }
        filterChain.doFilter(request, response);
    }
}
