package com.example.inframanager.deployment;

import java.net.http.HttpClient;

import com.example.inframanager.event.InboundEventIngestService;
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

/**
 * Всё, что нужно запасному пути с опросом; поднимается, только когда
 * {@code infra-manager.bamboo.source=poll}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "infra-manager.bamboo", name = "source", havingValue = "poll")
public class BambooPollConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(BambooPollConfig.class);

    private final BambooProperties properties;
    private final WorkerProperties workerProperties;
    private final ObjectProvider<BambooDeploymentPoller> poller;

    public BambooPollConfig(BambooProperties properties,
                            WorkerProperties workerProperties,
                            ObjectProvider<BambooDeploymentPoller> poller) {
        this.poller = poller;
        if (!StringUtils.hasText(properties.baseUrl()) || !StringUtils.hasText(properties.token())) {
            throw new IllegalStateException(
                    "infra-manager.bamboo.source=poll requires BAMBOO_BASE_URL and BAMBOO_TOKEN");
        }
        if (properties.poll().environments().isEmpty()) {
            throw new IllegalStateException(
                    "infra-manager.bamboo.source=poll requires infra-manager.bamboo.poll.environments");
        }
        this.properties = properties;
        this.workerProperties = workerProperties;
    }

    @Bean
    BambooClient bambooClient(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        RestClient restClient = builder
                .baseUrl(properties.baseUrl())
                // Personal access token'ы Data Center аутентифицируются как bearer-токены.
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .build();

        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(BambooClient.class);
    }

    @Bean
    BambooDeploymentPoller bambooDeploymentPoller(BambooClient client,
                                                  InboundEventIngestService ingestService,
                                                  ObjectMapper objectMapper) {
        return new BambooDeploymentPoller(client, properties, ingestService, objectMapper);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!workerProperties.schedulingEnabled()) {
            return;
        }
        // Достаётся через provider, а не внедряется: поллер — это @Bean этого же класса,
        // так что зависимость от него в конструкторе была бы циклической.
        registrar.addFixedDelayTask(
                () -> poller.getObject().runOnce(), properties.poll().interval());
        log.info("Bamboo polling scheduled every {} for {} environment(s)",
                properties.poll().interval(), properties.poll().environments().size());
    }
}
