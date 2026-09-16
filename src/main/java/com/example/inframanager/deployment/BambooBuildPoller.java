package com.example.inframanager.deployment;

import com.example.inframanager.event.EventSource;
import com.example.inframanager.event.InboundEventIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Ловит то, что {@link BambooDeploymentPoller} в принципе не может увидеть: сборку,
 * упавшую раньше релиза. Опрос окружений деплоя тут бессилен — результата деплоя для
 * такой сборки не существует вовсе, — поэтому опрашивается сам билд-план.
 *
 * <p>Успешные сборки не сохраняются: там, где есть деплой-проект, об успехе и так
 * объявит {@link DeploymentEventHandler}, а плодить о нём вторую запись незачем.
 */
public class BambooBuildPoller {

    private static final Logger log = LoggerFactory.getLogger(BambooBuildPoller.class);

    /** Без него результаты приходят голыми: ни времени завершения, ни причины запуска. */
    private static final String EXPAND = "results.result";

    private final BambooClient client;
    private final BambooProperties properties;
    private final InboundEventIngestService ingestService;
    private final ObjectMapper objectMapper;

    public BambooBuildPoller(BambooClient client,
                             BambooProperties properties,
                             InboundEventIngestService ingestService,
                             ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.ingestService = ingestService;
        this.objectMapper = objectMapper;
    }

    /** @return сколько ранее не виденных провалов записал этот проход */
    public int runOnce() {
        int ingested = 0;
        for (BambooProperties.Poll.BuildPlan plan : properties.poll().buildPlans()) {
            try {
                ingested += pollPlan(plan);
            } catch (Exception e) {
                // Один недоступный план не должен останавливать остальные.
                log.warn("Failed to poll Bamboo build plan {} ({})", plan.key(), plan.environmentName(), e);
            }
        }
        return ingested;
    }

    private int pollPlan(BambooProperties.Poll.BuildPlan plan) {
        BambooClient.PlanResults response =
                client.planResults(plan.key(), properties.poll().maxResults(), EXPAND);
        if (response == null) {
            return 0;
        }

        int ingested = 0;
        for (BambooClient.PlanResults.Summary result : response.resultList()) {
            if (!result.isFinished() || result.isSuccessful()) {
                continue;
            }
            BambooBuildEvent event = toEvent(result, plan);
            if (ingestService.ingest(EventSource.BAMBOO, result.buildResultKey(), "build",
                    objectMapper.writeValueAsString(event))) {
                ingested++;
            }
        }
        return ingested;
    }

    private BambooBuildEvent toEvent(BambooClient.PlanResults.Summary result, BambooProperties.Poll.BuildPlan plan) {
        return new BambooBuildEvent(
                result.buildResultKey(),
                result.buildState(),
                plan.projectName(),
                plan.environmentName(),
                result.buildStartedTime(),
                result.buildCompletedTime(),
                result.buildReason(),
                properties.baseUrl() + "/browse/" + result.buildResultKey());
    }
}
