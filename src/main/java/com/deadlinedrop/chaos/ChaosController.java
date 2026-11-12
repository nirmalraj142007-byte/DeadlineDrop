package com.deadlinedrop.chaos;

import com.deadlinedrop.api.dto.ChaosDtos.HaltResponse;
import com.deadlinedrop.api.dto.ChaosDtos.StatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * Failure injection for the evidence runs (F1: an instance dies mid-rush). Exists only when
 * {@code dd.chaos.enabled=true} (env {@code CHAOS_ENABLED}); with the flag off the routes are not
 * registered at all, so they answer 404 instead of advertising themselves with a 403.
 */
@RestController
@Profile("!storage")
@ConditionalOnProperty(name = "dd.chaos.enabled", havingValue = "true")
public class ChaosController {

    private static final Logger log = LoggerFactory.getLogger(ChaosController.class);

    private final Halter halter;
    private final String instanceName;

    public ChaosController(Halter halter) {
        this.halter = Objects.requireNonNull(halter, "halter must not be null");
        this.instanceName = instanceName();
    }

    @PostMapping("/admin/chaos/halt")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<HaltResponse> halt() {
        log.atWarn().addKeyValue("event", "chaos_halt_requested").addKeyValue("instance", instanceName)
                .log("admin requested an abrupt halt of this instance");
        halter.halt();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new HaltResponse(instanceName));
    }

    @GetMapping("/admin/chaos/status")
    @PreAuthorize("hasRole('ADMIN')")
    public StatusResponse status() {
        return new StatusResponse(true);
    }

    /** The Cloud Run revision when there is one, otherwise the host name, otherwise the process id. */
    private static String instanceName() {
        String revision = System.getenv("K_REVISION");
        if (revision != null && !revision.isBlank()) {
            return revision;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            log.atWarn().setCause(e).log("host name unavailable; naming this instance by process id");
            return "pid-" + ProcessHandle.current().pid();
        }
    }
}
