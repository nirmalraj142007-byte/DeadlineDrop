package com.deadlinedrop.chaos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The real crash for failure test F1: {@link Runtime#halt(int)} skips shutdown hooks, so Spring
 * does not close the pool or finish in-flight requests, exactly like a killed instance. It runs on
 * a separate thread after a short delay so the 202 that announced it reaches the caller first.
 */
@Component
@Profile("!storage")
@ConditionalOnProperty(name = "dd.chaos.enabled", havingValue = "true")
public class RuntimeHalter implements Halter {

    static final Duration FLUSH_DELAY = Duration.ofMillis(200);

    private static final Logger log = LoggerFactory.getLogger(RuntimeHalter.class);

    @Override
    public void halt() {
        Thread.ofPlatform().name("chaos-halt").start(() -> {
            try {
                Thread.sleep(FLUSH_DELAY);
            } catch (InterruptedException e) {
                // A halt was requested; being interrupted only means it happens a little sooner.
                Thread.currentThread().interrupt();
                log.atWarn().addKeyValue("event", "chaos_halt_interrupted").setCause(e)
                        .log("flush delay interrupted; halting now");
            }
            log.atWarn().addKeyValue("event", "chaos_halt").log("halting the JVM with exit status 1");
            Runtime.getRuntime().halt(1);
        });
    }
}
