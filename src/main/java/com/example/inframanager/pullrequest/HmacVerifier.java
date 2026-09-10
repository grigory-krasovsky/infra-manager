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
 * Проверяет {@code X-Hub-Signature}, который Bitbucket присылает, когда у вебхука
 * задан секрет.
 *
 * <p>HMAC считается по ровно тем байтам, что прислал Bitbucket. Разбор тела в JSON и
 * его повторная сериализация — а именно это и происходит, если контроллер принимает
 * типизированный {@code @RequestBody} — меняют пробелы, и тогда ни одна подпись не
 * сойдётся. Поэтому контроллер принимает {@code byte[]}.
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

        // За постоянное время: побайтовое сравнение выдаёт, какая часть поддельной
        // подписи была угадана верно.
        return MessageDigest.isEqual(expected, provided);
    }
}
