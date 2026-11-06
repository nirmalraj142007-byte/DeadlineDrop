package com.deadlinedrop.security;

import com.deadlinedrop.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializes RFC 9457 ProblemDetail payloads directly to the HTTP response
 * for Spring Security authentication and authorization failures.
 */
@Component
public class ProblemDetailWriter {

    private final ObjectMapper objectMapper;

    public ProblemDetailWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(
            HttpServletResponse response,
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId == null || requestId.isBlank()) {
            requestId = response.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        }
        if ((requestId == null || requestId.isBlank()) && request != null) {
            requestId = (String) request.getAttribute(RequestIdFilter.MDC_KEY);
        }
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        ProblemDetail problemDetail = ProblemDetail.forStatus(status);
        problemDetail.setType(URI.create("https://deadlinedrop.dev/problems/" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problemDetail.setTitle(status.getReasonPhrase());
        problemDetail.setDetail(detail != null ? detail : status.getReasonPhrase());
        if (request != null && request.getRequestURI() != null) {
            problemDetail.setInstance(URI.create(request.getRequestURI()));
        }
        problemDetail.setProperty("code", code);
        problemDetail.setProperty("requestId", requestId);

        objectMapper.writeValue(response.getOutputStream(), problemDetail);
    }
}
