package com.deadlinedrop.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filter that records request arrival timing for naive multipart submissions.
 * Runs immediately after {@link RequestIdFilter} so headers are received and
 * a request ID is established.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RequestTimingLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestTimingLogFilter.class);

    private static final Pattern NAIVE_SUBMISSION_PATTERN = Pattern.compile(
            "^/api/assignments/[^/]+/naive-submissions/?$"
    );

    private final Clock clock;

    public RequestTimingLogFilter(ObjectProvider<Clock> clockProvider) {
        Clock resolved = clockProvider.getIfAvailable();
        this.clock = resolved != null ? resolved : Clock.systemUTC();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return !NAIVE_SUBMISSION_PATTERN.matcher(uri).matches();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long epochMs = clock.millis();

        String requestId = (String) request.getAttribute(RequestIdFilter.MDC_KEY);
        if (requestId == null || requestId.isBlank()) {
            requestId = MDC.get(RequestIdFilter.MDC_KEY);
        }
        if (requestId == null || requestId.isBlank()) {
            requestId = request.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        }

        long contentLength = request.getContentLengthLong();

        var logBuilder = log.atInfo()
                .addKeyValue("event", "request_arrived")
                .addKeyValue("epochMs", epochMs)
                .addKeyValue("contentLength", contentLength);

        if (MDC.get(RequestIdFilter.MDC_KEY) == null && requestId != null && !requestId.isBlank()) {
            logBuilder.addKeyValue("requestId", requestId);
        }

        logBuilder.log("request_arrived");

        filterChain.doFilter(request, response);
    }
}
