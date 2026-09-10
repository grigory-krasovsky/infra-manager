package com.example.inframanager.notify;

import java.net.http.HttpClient;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the Telegram client explicitly rather than through {@code @ImportHttpServices}
 * groups: each integration in this service has its own base URL and its own auth
 * scheme, so there is no shared group to configure.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "infra-manager.telegram", name = "enabled", havingValue = "true")
public class TelegramClientConfig {

    private final TelegramProperties properties;

    public TelegramClientConfig(TelegramProperties properties) {
        if (!StringUtils.hasText(properties.botToken())) {
            // Fail at startup rather than at the first deployment, when a missing
            // notification is the thing nobody notices.
            throw new IllegalStateException(
                    "infra-manager.telegram.enabled=true but no bot token set (TELEGRAM_BOT_TOKEN)");
        }
        this.properties = properties;
    }

    @Bean
    TelegramClient telegramClient(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        RestClient restClient = builder
                .baseUrl(properties.baseUrl() + "/bot" + properties.botToken())
                .requestFactory(requestFactory)
                .build();

        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(TelegramClient.class);
    }

    @Bean
    TelegramSender telegramSender(TelegramClient telegramClient, ObjectMapper objectMapper) {
        return new TelegramSender(telegramClient, objectMapper);
    }
}
