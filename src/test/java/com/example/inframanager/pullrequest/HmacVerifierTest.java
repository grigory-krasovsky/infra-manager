package com.example.inframanager.pullrequest;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HmacVerifierTest {

    private static final String SECRET = "s3cr3t";
    private static final byte[] BODY = "{\"eventKey\":\"pr:opened\"}".getBytes(StandardCharsets.UTF_8);

    private final HmacVerifier verifier = new HmacVerifier(SECRET);

    @Test
    void acceptsASignatureOverTheExactBytes() {
        assertThat(verifier.verify(BODY, sign(BODY))).isTrue();
    }

    @Test
    void rejectsASignatureFromADifferentSecret() {
        String foreign = sign(BODY, "another-secret");

        assertThat(verifier.verify(BODY, foreign)).isFalse();
    }

    @Test
    void rejectsWhenTheBodyWasReformatted() {
        // Сбой, ради которого этот тест и существует: разбор JSON с последующей
        // сериализацией меняет одни лишь пробелы — и подпись перестаёт сходиться.
        byte[] reformatted = "{ \"eventKey\" : \"pr:opened\" }".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify(reformatted, sign(BODY))).isFalse();
    }

    @Test
    void rejectsAMissingHeader() {
        assertThat(verifier.verify(BODY, null)).isFalse();
        assertThat(verifier.verify(BODY, "")).isFalse();
    }

    @Test
    void rejectsAHeaderWithoutTheAlgorithmPrefix() {
        String bare = sign(BODY).substring("sha256=".length());

        assertThat(verifier.verify(BODY, bare)).isFalse();
    }

    @Test
    void rejectsAHeaderThatIsNotHex() {
        assertThat(verifier.verify(BODY, "sha256=not-hex-at-all")).isFalse();
    }

    private static String sign(byte[] body) {
        return sign(body, SECRET);
    }

    private static String sign(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
