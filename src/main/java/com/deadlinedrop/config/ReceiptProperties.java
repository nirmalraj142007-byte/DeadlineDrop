package com.deadlinedrop.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

/**
 * Signed commit receipts ({@code dd.receipt.*}). {@code enabled} (env {@code RECEIPT_ENABLED},
 * default true) decides whether a commit that cannot reach the database gets a receipt or a 503.
 * {@code hmacKey} (env {@code RECEIPT_HMAC_KEY}) is base64 and must decode to at least 32 bytes;
 * it is required even with receipts disabled, so receipts already issued can still be redeemed.
 */
@ConfigurationProperties(prefix = "dd.receipt")
@Validated
@Profile("!storage")
public record ReceiptProperties(
        boolean enabled,
        @NotBlank(message = "RECEIPT_HMAC_KEY must be set (generate one with: openssl rand -base64 32)")
        String hmacKey) {
}
