package com.deadlinedrop.api.dto;

/** Public deployment settings the front end reads before login. */
public record ConfigResponse(String arm, String armLabel, long maxBytesCap, String consentVersion, long serverEpochMs) {
}
