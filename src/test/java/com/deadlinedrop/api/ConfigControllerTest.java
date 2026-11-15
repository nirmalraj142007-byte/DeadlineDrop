package com.deadlinedrop.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC);

    private static String[] armAndLabel(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        var body = new ConfigController(env, CLOCK).config().getBody();
        assertThat(body).isNotNull();
        assertThat(body.serverEpochMs()).isEqualTo(CLOCK.millis());
        return new String[] {body.arm(), body.armLabel()};
    }

    @Test
    void naiveProfileIsArmA() {
        assertThat(armAndLabel("local", "naive")).containsExactly("A", "Arm A: stamped when the upload finishes");
    }

    @Test
    void directFinalizeProfileIsArmB() {
        assertThat(armAndLabel("cloud", "direct-finalize")).containsExactly("B", "Arm B: stamped when the upload finishes");
    }

    @Test
    void noArmProfileIsArmC() {
        assertThat(armAndLabel("local")).containsExactly("C", "DeadlineDrop: time locked when you commit");
    }
}
