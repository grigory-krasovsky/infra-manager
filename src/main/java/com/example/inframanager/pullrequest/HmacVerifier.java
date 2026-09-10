package com.example.inframanager.pullrequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * Verifies the {@code X-Hub-Signature} Bitbucket sends when a webhook has a secret.
 *
 * <p>The HMAC is computed over the exact bytes Bitbucket sent. Parsing the body to
 * JSON and re-serialising it -- which is what happens if a controller takes a typed
 * {@code @RequestBody} -- changes whitespace and makes every signature fail. That is
 * why the controller takes {@code byte[]}.
 */
public class HmacVerifier {

    private static final Logger log = LoggerFactory.getLogger(HmacVerifier.class);
    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private final SecretKeySpec key;

    public HmacVerifier(String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public boolean verify(byte[] body, String signatureHeader) {
        if (!StringUtils.hasText(signatureHeader) || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }

        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signatureHeader.substring(PREFIX.length()).trim());
        } catch (IllegalArgumentException e) {
            log.warn("X-Hub-Signature was not valid hex");
            return false;
        }

        byte[] expected;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            expected = mac.doFinal(body);
        } catch (Exception e) {
            throw new IllegalStateException("Could not compute HMAC-SHA256", e);
        }

        // Constant time: a byte-by-byte comparison leaks how much of a forged
        // signature was correct.
        return MessageDigest.isEqual(expected, provided);
    }
}
