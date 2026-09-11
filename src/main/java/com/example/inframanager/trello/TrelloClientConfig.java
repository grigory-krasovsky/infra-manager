package com.example.inframanager.trello;

import java.net.http.HttpClient;

import com.example.inframanager.outbound.OutboundTaskService;
import com.example.inframanager.pullrequest.LifecycleProperties;
import com.example.inframanager.pullrequest.PrCardLinkRepository;
import com.example.inframanager.work.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "infra-manager.trello", name = "enabled", havingValue = "true")
public class TrelloClientConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(TrelloClientConfig.class);

    private final TrelloProperties properties;
    private final WorkerProperties workerProperties;
    private final ObjectProvider<TrelloReconciliationPoller> reconciliationPoller;
    private final ObjectProvider<TrelloCompletionPoller> completionPoller;

    public TrelloClientConfig(TrelloProperties properties,
                              WorkerProperties workerProperties,
                              ObjectProvider<TrelloReconciliationPoller> reconciliationPoller,
                              ObjectProvider<TrelloCompletionPoller> completionPoller) {
        if (!StringUtils.hasText(properties.key()) || !StringUtils.hasText(properties.token())) {
            throw new IllegalStateException(
                    "infra-manager.trello.enabled=true requires TRELLO_KEY and TRELLO_TOKEN; "
                            + "see docs/runbook.md for how to obtain them via a Power-Up");
        }
        this.properties = properties;
        this.workerProperties = workerProperties;
        this.reconciliationPoller = reconciliationPoller;
        this.completionPoller = completionPoller;
    }

    @Bean
    TrelloClient trelloClient(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        RestClient restClient = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .build();

        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(TrelloClient.class);
    }

    @Bean
    TrelloListResolver trelloListResolver(TrelloClient client) {
        return new TrelloListResolver(client, properties);
    }

    @Bean
    TrelloLabelResolver trelloLabelResolver(TrelloClient client) {
        return new TrelloLabelResolver(client, properties);
    }

    @Bean
    TrelloMemberResolver trelloMemberResolver(TrelloClient client) {
        return new TrelloMemberResolver(client, properties);
    }

    @Bean
    TrelloChecklistSync trelloChecklistSync(TrelloClient client) {
        return new TrelloChecklistSync(client, properties);
    }

    @Bean
    TrelloSender trelloSender(TrelloClient client,
                              TrelloListResolver listResolver,
                              TrelloLabelResolver labelResolver,
                              TrelloMemberResolver memberResolver,
                              TrelloChecklistSync checklistSync,
                              PrCardLinkRepository linkRepository,
                              ObjectMapper objectMapper) {
        return new TrelloSender(client, listResolver, labelResolver, memberResolver, checklistSync,
                properties, linkRepository, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "infra-manager.trello.reconciliation", name = "enabled", havingValue = "true")
    TrelloReconciliationPoller trelloReconciliationPoller(TrelloClient client,
                                                          TrelloListResolver listResolver,
                                                          PrCardLinkRepository linkRepository,
                                                          OutboundTaskService taskService,
                                                          ObjectMapper objectMapper) {
        return new TrelloReconciliationPoller(
                client, listResolver, properties, linkRepository, taskService, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "infra-manager.trello.completion", name = "enabled", havingValue = "true")
    TrelloCompletionPoller trelloCompletionPoller(TrelloListResolver listResolver,
                                                  LifecycleProperties lifecycle,
                                                  PrCardLinkRepository linkRepository,
                                                  OutboundTaskService taskService,
                                                  ObjectMapper objectMapper) {
        return new TrelloCompletionPoller(
                listResolver, lifecycle, properties, linkRepository, taskService, objectMapper);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!workerProperties.schedulingEnabled()) {
            return;
        }
        // Через provider, потому что поллеры — это @Bean'ы этого же класса.
        if (properties.reconciliation().enabled()) {
            registrar.addFixedDelayTask(
                    () -> reconciliationPoller.getObject().runOnce(), properties.reconciliation().interval());
            log.info("Trello reconciliation scheduled every {}", properties.reconciliation().interval());
        }
        if (properties.completion().enabled()) {
            registrar.addFixedDelayTask(
                    () -> completionPoller.getObject().runOnce(), properties.completion().interval());
            log.info("Trello card completion scheduled every {}, after {} in the merged list",
                    properties.completion().interval(), properties.completion().after());
        }
    }
}
