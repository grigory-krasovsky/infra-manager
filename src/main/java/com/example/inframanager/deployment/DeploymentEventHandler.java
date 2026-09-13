package com.example.inframanager.deployment;

import java.time.Instant;

import com.example.inframanager.event.EventSource;
import com.example.inframanager.event.InboundEvent;
import com.example.inframanager.event.InboundEventHandler;
import com.example.inframanager.notify.TelegramNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Превращает сохранённую доставку от Bamboo в запись о деплое, а когда деплой
 * действительно завершился — в объявление в Telegram.
 *
 * <p>Работает внутри транзакции входящего воркера, поэтому запись о деплое, отметка
 * «объявлено» и постановка сообщения в очередь происходят либо все, либо ни одна.
 */
@Component
public class DeploymentEventHandler implements InboundEventHandler {

    private static final Logger log = LoggerFactory.getLogger(DeploymentEventHandler.class);

    private final DeploymentRecordRepository repository;
    private final DeploymentMessageRenderer renderer;
    private final DeploymentSubjectEnricher subjectEnricher;
    private final TelegramNotifier notifier;
    private final BambooProperties properties;
    private final ObjectMapper objectMapper;

    public DeploymentEventHandler(DeploymentRecordRepository repository,
                                  DeploymentMessageRenderer renderer,
                                  DeploymentSubjectEnricher subjectEnricher,
                                  TelegramNotifier notifier,
                                  BambooProperties properties,
                                  ObjectMapper objectMapper) {
        this.repository = repository;
        this.renderer = renderer;
        this.subjectEnricher = subjectEnricher;
        this.notifier = notifier;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(EventSource source) {
        return source == EventSource.BAMBOO;
    }

    @Override
    public void handle(InboundEvent event) {
        BambooDeploymentEvent parsed = resolveProjectName(
                objectMapper.readValue(event.getPayload(), BambooDeploymentEvent.class));
        if (parsed.deploymentResultId() == null) {
            // Неисправимо: повтор не добавит поле. Пусть исчерпает попытки и осядет в
            // FAILED, где плохой payload останется на виду рядом с ошибкой.
            throw new IllegalArgumentException(
                    "Bamboo payload has no deploymentResultId; check the webhook template");
        }

        DeploymentRecord record = repository.findByBambooDeploymentResultId(parsed.deploymentResultId())
                .orElseGet(() -> repository.save(new DeploymentRecord(parsed)));
        record.apply(parsed);

        if (record.getNotifiedAt() != null) {
            log.debug("Deployment {} already announced", parsed.deploymentResultId());
            return;
        }
        if (!parsed.isNotifiable()) {
            log.debug("Deployment {} is {}/{}; recorded but not announced",
                    parsed.deploymentResultId(), parsed.normalisedStatus(), parsed.lifeCycleState());
            return;
        }
        if (isTooOldToAnnounce(parsed)) {
            // Помечаем объявленным, чтобы более позднее наблюдение того же деплоя не
            // воскресило его, когда сравнивать с часами уже перестанут.
            log.info("Deployment {} of {} to {} finished at {}; too old to announce",
                    parsed.deploymentResultId(), record.getProjectName(),
                    record.getEnvironmentName(), parsed.finishedInstant());
            record.markNotified();
            return;
        }

        notifier.notify(
                parsed.environmentNameOrUnknown(),
                "deploy:" + parsed.deploymentResultId(),
                // Никогда не бросает: уведомление без строки про задачу лучше, чем
                // уведомление, застрявшее из-за недоступного Bamboo.
                renderer.render(parsed, subjectEnricher.subjectFor(parsed)));
        record.markNotified();
        log.info("Announced deployment {} of {} to {} ({})",
                parsed.deploymentResultId(), record.getProjectName(),
                record.getEnvironmentName(), record.getStatus());
    }

    /**
     * Деплой без времени завершения объявляется: так бывает только у payload'ов вебхука,
     * которые приходят в момент окончания деплоя, а промолчать о настоящем деплое — сбой
     * похуже.
     */
    private boolean isTooOldToAnnounce(BambooDeploymentEvent event) {
        Instant finished = event.finishedInstant();
        return finished != null
                && finished.isBefore(Instant.now().minus(properties.maxNotificationAge()));
    }

    /**
     * Подставляет имя проекта из конфигурации, когда payload несёт только id, — так
     * ведут себя шаблоны вебхуков Bamboo, которые имя не отдают.
     */
    private BambooDeploymentEvent resolveProjectName(BambooDeploymentEvent event) {
        if (event.hasProjectName() || event.deploymentProjectId() == null) {
            return event;
        }
        String configured = properties.projectNames().get(event.deploymentProjectId());
        return configured == null ? event : event.withProjectName(configured);
    }
}
