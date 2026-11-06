package com.deadlinedrop.api;

import org.springframework.http.HttpStatus;

/**
 * Domain-aware HTTP API exception carrying an HTTP status, error code, and detail message.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String detail;

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
        this.detail = detail;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getDetail() {
        return detail;
    }

    public static ApiException notFound(String code, String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, code, detail);
    }

    public static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, detail);
    }

    public static ApiException forbidden(String code, String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, code, detail);
    }

    public static ApiException unprocessable(String code, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, detail);
    }

    public static ApiException gone(String code, String detail) {
        return new ApiException(HttpStatus.GONE, code, detail);
    }

    public static ApiException badRequest(String code, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, detail);
    }

    public static ApiException payloadTooLarge(String code, String detail) {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, code, detail);
    }

    public static ApiException unsupportedType(String code, String detail) {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, code, detail);
    }
}
