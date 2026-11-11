package com.deadlinedrop.storage;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Utility for HMAC-SHA256 signature calculation and constant-time verification.
 */
final class StorageHmac {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private StorageHmac() {
    }

    static String sign(String data, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Storage HMAC secret is missing or blank");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] hmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256", e);
        }
    }

    static boolean verify(String data, String secret, String presentedSig) {
        if (presentedSig == null || secret == null || secret.isBlank()) {
            return false;
        }
        byte[] expected = Base64.getUrlDecoder().decode(sign(data, secret));
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(presentedSig);
        } catch (IllegalArgumentException e) {
            // A signature that is not valid Base64url cannot match: the caller answers 403.
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }
}
