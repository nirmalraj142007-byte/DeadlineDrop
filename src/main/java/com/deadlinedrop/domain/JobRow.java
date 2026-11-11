package com.deadlinedrop.domain;

import java.util.UUID;

/** A claimed row of {@code jobs}: what a drainer needs to run it and to schedule its retry. */
public record JobRow(long id, UUID submissionId, int attempts) {
}
