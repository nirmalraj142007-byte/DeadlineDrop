package com.deadlinedrop.api;

/**
 * Standard error codes used across DeadlineDrop API responses (RFC 9457).
 */
public final class ErrorCodes {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String MISSING_IDEMPOTENCY_KEY = "MISSING_IDEMPOTENCY_KEY";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String ASSIGNMENT_NOT_FOUND = "ASSIGNMENT_NOT_FOUND";
    public static final String SUBMISSION_NOT_FOUND = "SUBMISSION_NOT_FOUND";
    public static final String NOT_ENROLLED = "NOT_ENROLLED";
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
    public static final String VERSION_CONFLICT = "VERSION_CONFLICT";
    public static final String WRONG_STATE = "WRONG_STATE";
    public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
    public static final String TYPE_NOT_ALLOWED = "TYPE_NOT_ALLOWED";
    public static final String SIZE_MISMATCH = "SIZE_MISMATCH";
    public static final String CONTENT_TYPE_MISMATCH = "CONTENT_TYPE_MISMATCH";
    public static final String BYTES_NOT_FOUND = "BYTES_NOT_FOUND";
    public static final String HASH_MISMATCH_AFTER_DEADLINE = "HASH_MISMATCH_AFTER_DEADLINE";
    public static final String GRACE_OVER = "GRACE_OVER";
    public static final String GRACE_NOT_OVER = "GRACE_NOT_OVER";
    public static final String NOT_UPLOADED = "NOT_UPLOADED";
    public static final String RECEIPT_INVALID = "RECEIPT_INVALID";
    public static final String RECEIPT_STALE = "RECEIPT_STALE";
    public static final String CONSENT_REQUIRED = "CONSENT_REQUIRED";
    public static final String DATABASE_UNAVAILABLE = "DATABASE_UNAVAILABLE";
    public static final String INTERNAL = "INTERNAL";

    private ErrorCodes() {
    }
}
