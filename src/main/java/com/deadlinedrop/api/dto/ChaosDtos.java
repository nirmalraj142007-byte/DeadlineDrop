package com.deadlinedrop.api.dto;

/**
 * Response bodies of the failure-injection endpoints under {@code /admin/chaos}.
 */
public final class ChaosDtos {

    private ChaosDtos() {
    }

    /** {@code halting}: the Cloud Run revision (or host) that is about to stop. */
    public record HaltResponse(String halting) {
    }

    public record StatusResponse(boolean enabled) {
    }
}
