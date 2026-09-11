package com.example.inframanager.pullrequest;

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
 * Всё, что нужно пути с опросом пул-реквестов; поднимается, только когда
 * {@code infra-manager.bitbucket.poll.enabled=true}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "infra-manager.bitbucket.poll", name = "enabled", havingValue = "true")
public class BitbucketPollConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(BitbucketPollConfig.class);

    private final BitbucketProperties properties;
    private final LifecycleProperties lifecycle;
    private final WorkerProperties workerProperties;
    private final ObjectProvider<BitbucketPrPoller> poller;

    public BitbucketPollConfig(BitbucketProperties properties,
                               LifecycleProperties lifecycle,
                               WorkerProperties workerProperties,
                               ObjectProvider<BitbucketPrPoller> poller) {
        if (!StringUtils.hasText(properties.baseUrl()) || !StringUtils.hasText(properties.token())) {
            throw new IllegalStateException(
                    "infra-manager.bitbucket.poll.enabled=true requires BITBUCKET_BASE_URL and BITBUCKET_TOKEN");
        }
        if (lifecycle.repos().isEmpty()) {
            throw new IllegalStateException(
                    "infra-manager.bitbucket.poll.enabled=true requires infra-manager.lifecycle.repos");
        }
        this.properties = properties;
        this.lifecycle = lifecycle;
        this.workerProperties = workerProperties;
        this.poller = poller;
    }

    @Bean
    BitbucketClient bitbucketClient(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        RestClient restClient = builder
                .baseUrl(properties.baseUrl())
                // HTTP access token'ы Data Center аутентифицируются как bearer-токены.
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .build();

        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(BitbucketClient.class);
    }

    @Bean
    BitbucketPrPoller bitbucketPrPoller(BitbucketClient client,
                                        PrPollStateRepository stateRepository,
                                        PrTaskReader taskReader,
                                        InboundEventIngestService ingestService,
                                        ObjectMapper objectMapper) {
        return new BitbucketPrPoller(
                client, properties, lifecycle, stateRepository, taskReader, ingestService, objectMapper);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!workerProperties.schedulingEnabled()) {
            return;
        }
        // Через provider, потому что поллер — это @Bean этого же класса.
        registrar.addFixedDelayTask(() -> poller.getObject().runOnce(), properties.poll().interval());
        log.info("Bitbucket polling scheduled every {} for {} repository(ies)",
                properties.poll().interval(), lifecycle.repos().size());
    }
}
