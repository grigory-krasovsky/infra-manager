package com.example.inframanager.deployment;

import java.util.List;

import com.example.inframanager.event.EventSource;
import com.example.inframanager.event.InboundEventIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Запасной способ приёма для установок Bamboo без шаблонов вебхуков.
 *
 * <p>Своего курсора не держит: каждый проход перечитывает последние несколько
 * результатов по каждому окружению и отдаёт их {@link InboundEventIngestService},
 * ограничение уникальности которого отбрасывает уже виденные. Таблица с курсором была
 * бы ещё одной вещью, которую можно испортить после перезапуска, — и без всякой выгоды.
 */
public class BambooDeploymentPoller {

    private static final Logger log = LoggerFactory.getLogger(BambooDeploymentPoller.class);

    private final BambooClient client;
    private final BambooProperties properties;
    private final InboundEventIngestService ingestService;
    private final ObjectMapper objectMapper;

    public BambooDeploymentPoller(BambooClient client,
                                  BambooProperties properties,
                                  InboundEventIngestService ingestService,
                                  ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.ingestService = ingestService;
        this.objectMapper = objectMapper;
    }

    /** @return сколько ранее не виденных деплоев записал этот проход */
    public int runOnce() {
        int ingested = 0;
        for (BambooProperties.Poll.Environment environment : properties.poll().environments()) {
            try {
                ingested += pollEnvironment(environment);
            } catch (Exception e) {
                // Одно недоступное окружение не должно останавливать остальные.
                log.warn("Failed to poll Bamboo environment {} ({})",
                        environment.id(), environment.environmentName(), e);
            }
        }
        return ingested;
    }

    private int pollEnvironment(BambooProperties.Poll.Environment environment) {
        BambooClient.EnvironmentResults response =
                client.environmentResults(environment.id(), properties.poll().maxResults());

        List<BambooClient.DeploymentResult> results =
                response == null || response.results() == null ? List.of() : response.results();

        int ingested = 0;
        for (BambooClient.DeploymentResult result : results) {
            if (!result.isFinished()) {
                continue;
            }
            BambooDeploymentEvent event = toEvent(result, environment);
            String externalId = result.id() + ":" + event.normalisedStatus();
            if (ingestService.ingest(EventSource.BAMBOO, externalId, "deployment",
                    objectMapper.writeValueAsString(event))) {
                ingested++;
            }
        }
        return ingested;
    }

    /** Приводит к той же форме, что сохраняет путь с вебхуком, чтобы обоим хватило одного обработчика. */
    private BambooDeploymentEvent toEvent(BambooClient.DeploymentResult result,
                                          BambooProperties.Poll.Environment environment) {
        return new BambooDeploymentEvent(
                result.id(),
                result.deploymentState(),
                result.lifeCycleState(),
                // Имена здесь берутся из конфигурации: endpoint с результатами их не несёт,
                // а требование задать их в конфиге — это то, что оставляет нам один вызов на проход.
                environment.projectName(),
                null,
                environment.environmentName(),
                result.deploymentVersionName(),
                result.startedDate() == null ? null : String.valueOf(result.startedDate()),
                result.finishedDate() == null ? null : String.valueOf(result.finishedDate()),
                result.reasonSummary());
    }
}
