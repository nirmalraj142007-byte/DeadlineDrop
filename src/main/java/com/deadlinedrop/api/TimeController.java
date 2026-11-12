package com.deadlinedrop.api;

import com.deadlinedrop.api.dto.DomainDtos.TimeResponse;
import com.deadlinedrop.security.PublicEndpoint;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Objects;

@RestController
@RequestMapping("/api/time")
public class TimeController {

    private final Clock clock;

    public TimeController(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @PublicEndpoint
    @GetMapping
    public ResponseEntity<TimeResponse> getTime() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new TimeResponse(clock.millis()));
    }
}
