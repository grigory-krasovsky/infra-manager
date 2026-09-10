package com.example.inframanager.pullrequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.example.inframanager.event.EventSource;
import com.example.inframanager.event.InboundEventIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Принимает события пул-реквестов от Bitbucket.
 *
 * <p>Тело намеренно берётся как {@code byte[]}: HMAC считается по ровно тем байтам,
 * что были отправлены, поэтому подпись надо проверить до того, как до тела доберётся
 * Jackson.
 */
@RestController
@RequestMapping("/webhooks/bitbucket")
@ConditionalOnProperty(prefix = "infra-manager.bitbucket", name = "enabled", havingValue = "true")
public class BitbucketWebhookController {

    private static final Logger log = LoggerFactory.getLogger(BitbucketWebhookController.class);
    private static final String EVENT_HEADER = "X-Event-Key";
    private static final String SIGNATURE_HEADER = "X-Hub-Signature";

    private final HmacVerifier verifier;
    private final InboundEventIngestService ingestService;

    public BitbucketWebhookController(BitbucketProperties properties,
                                      InboundEventIngestService ingestService) {
        if (!StringUtils.hasText(properties.webhookSecret())) {
            throw new IllegalStateException(
                    "infra-manager.bitbucket.enabled=true requires a webhook secret (BITBUCKET_WEBHOOK_SECRET)");
        }
        this.verifier = new HmacVerifier(properties.webhookSecret());
        this.ingestService = ingestService;
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestBody byte[] body,
            @RequestHeader(name = EVENT_HEADER, required = false) String eventKey,
            @RequestHeader(name = SIGNATURE_HEADER, required = false) String signature) {

        if (!verifier.verify(body, signature)) {
            log.warn("Rejected Bitbucket webhook with bad or missing signature (event {})", eventKey);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!StringUtils.hasText(eventKey)) {
            log.warn("Rejected Bitbucket webhook without {}", EVENT_HEADER);
            return ResponseEntity.badRequest().build();
        }

        // Идентификатор доставки у Bitbucket не гарантированно одинаков при повторах,
        // поэтому личность события берётся из содержимого: точный повтор даёт тот же хеш,
        // а по-настоящему новое событие — нет.
        String externalId = digest(eventKey, body);
        ingestService.ingest(EventSource.BITBUCKET, externalId, eventKey,
                new String(body, StandardCharsets.UTF_8));
        return ResponseEntity.ok().build();
    }

    private static String digest(String eventKey, byte[] body) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(eventKey.getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) 0);
            return HexFormat.of().formatHex(sha256.digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
