package com.example.inframanager.trello;

import java.net.http.HttpClient;

import com.example.inframanager.outbound.OutboundTaskService;
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

    public TrelloClientConfig(TrelloProperties properties,
                              WorkerProperties workerProperties,
                              ObjectProvider<TrelloReconciliationPoller> reconciliationPoller) {
        if (!StringUtils.hasText(properties.key()) || !StringUtils.hasText(properties.token())) {
            throw new IllegalStateException(
                    "infra-manager.trello.enabled=true requires TRELLO_KEY and TRELLO_TOKEN; "
                            + "see docs/runbook.md for how to obtain them via a Power-Up");
        }
        this.properties = properties;
        this.workerProperties = workerProperties;
        this.reconciliationPoller = reconciliationPoller;
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
    TrelloSender trelloSender(TrelloClient client,
                              TrelloListResolver listResolver,
                              PrCardLinkRepository linkRepository,
                              ObjectMapper objectMapper) {
        return new TrelloSender(client, listResolver, properties, linkRepository, objectMapper);
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

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!workerProperties.schedulingEnabled() || !properties.reconciliation().enabled()) {
            return;
        }
        // Through a provider because the poller is a @Bean of this same class.
        registrar.addFixedDelayTask(
                () -> reconciliationPoller.getObject().runOnce(), properties.reconciliation().interval());
        log.info("Trello reconciliation scheduled every {}", properties.reconciliation().interval());
    }
}
