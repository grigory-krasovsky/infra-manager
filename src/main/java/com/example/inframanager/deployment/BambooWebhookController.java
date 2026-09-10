package com.example.inframanager.deployment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Принимает результаты деплоя от Bamboo.
 *
 * <p>Делает минимум и возвращает управление: аутентифицировать, записать, 200. Бюджет
 * повторных доставок у Bamboo конечен, поэтому всё медленное здесь рискует потерять
 * событие навсегда.
 *
 * <p>В отличие от Bitbucket, тело вебхука Bamboo — это то, что отрендерил наш же
 * шаблон, поэтому проверять HMAC не нужно: аутентификация — общий секрет, который мы
 * сами вписали в шаблон.
 */
@RestController
@RequestMapping("/webhooks/bamboo")
@ConditionalOnProperty(prefix = "infra-manager.bamboo", name = "source", havingValue = "webhook")
public class BambooWebhookController {

    private static final Logger log = LoggerFactory.getLogger(BambooWebhookController.class);
    private static final String SECRET_HEADER = "X-Infra-Manager-Secret";

    private final InboundEventIngestService ingestService;
    private final ObjectMapper objectMapper;
    private final byte[] expectedSecret;

    public BambooWebhookController(BambooProperties properties,
                                   InboundEventIngestService ingestService,
                                   ObjectMapper objectMapper) {
        if (!StringUtils.hasText(properties.webhookSecret())) {
            throw new IllegalStateException(
                    "infra-manager.bamboo.source=webhook requires a webhook secret (BAMBOO_WEBHOOK_SECRET)");
        }
        this.expectedSecret = properties.webhookSecret().getBytes(StandardCharsets.UTF_8);
        this.ingestService = ingestService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestBody String body,
            @RequestHeader(name = SECRET_HEADER, required = false) String headerSecret,
            // Принимается как запасной вариант для версий Bamboo, чьи шаблоны не умеют
            // ставить заголовки. Секреты в URL оседают в access-логах, так что заголовок
            // предпочтительнее.
            @RequestParam(name = "secret", required = false) String querySecret) {

        if (!secretMatches(headerSecret) && !secretMatches(querySecret)) {
            log.warn("Rejected Bamboo webhook with bad or missing secret");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        BambooDeploymentEvent event;
        try {
            event = objectMapper.readValue(body, BambooDeploymentEvent.class);
        } catch (RuntimeException e) {
            log.warn("Rejected unparseable Bamboo webhook body", e);
            return ResponseEntity.badRequest().build();
        }
        if (event.deploymentResultId() == null) {
            log.warn("Rejected Bamboo webhook without deploymentResultId");
            return ResponseEntity.badRequest().build();
        }

        // Статус входит в ключ, чтобы наблюдение «в очереди» и «завершено» были разными
        // событиями, а простая повторная доставка любого из них оставалась дубликатом.
        String externalId = event.deploymentResultId() + ":" + event.normalisedStatus();
        ingestService.ingest(EventSource.BAMBOO, externalId, "deployment", body);
        return ResponseEntity.ok().build();
    }

    private boolean secretMatches(String candidate) {
        if (!StringUtils.hasText(candidate)) {
            return false;
        }
        return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), expectedSecret);
    }
}
