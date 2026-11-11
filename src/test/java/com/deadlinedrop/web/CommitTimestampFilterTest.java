package com.deadlinedrop.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CommitTimestampFilterTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:15:30.123Z");

    private final CommitTimestampFilter filter = new CommitTimestampFilter(Clock.fixed(NOW, ZoneOffset.UTC));

    private MockHttpServletRequest run(String method, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).as("the chain must always continue").isNotNull();
        return request;
    }

    @Test
    void postToCommitPathSetsArrivalStamp() throws Exception {
        MockHttpServletRequest request = run("POST", "/api/assignments/" + UUID.randomUUID() + "/commits");

        assertThat(request.getAttribute(CommitTimestampFilter.ARRIVED_AT)).isEqualTo(NOW);
    }

    @Test
    void getToCommitPathIsNotStamped() throws Exception {
        MockHttpServletRequest request = run("GET", "/api/assignments/" + UUID.randomUUID() + "/commits");

        assertThat(request.getAttribute(CommitTimestampFilter.ARRIVED_AT)).isNull();
    }

    @Test
    void postToOtherPathsIsNotStamped() throws Exception {
        UUID id = UUID.randomUUID();
        assertThat(run("POST", "/api/assignments/" + id + "/naive-submissions")
                .getAttribute(CommitTimestampFilter.ARRIVED_AT)).isNull();
        assertThat(run("POST", "/api/auth/login").getAttribute(CommitTimestampFilter.ARRIVED_AT)).isNull();
        assertThat(run("POST", "/api/assignments/" + id + "/commits/extra")
                .getAttribute(CommitTimestampFilter.ARRIVED_AT)).isNull();
        assertThat(run("POST", "/api/assignments//commits").getAttribute(CommitTimestampFilter.ARRIVED_AT)).isNull();
    }
}
