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
 * Собирает клиент Telegram явно, а не через группы {@code @ImportHttpServices}: у каждой
 * интеграции в этом сервисе свой базовый URL и своя схема аутентификации, так что общей
 * группы, которую можно было бы настроить, попросту нет.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "infra-manager.telegram", name = "enabled", havingValue = "true")
public class TelegramClientConfig {

    private final TelegramProperties properties;

    public TelegramClientConfig(TelegramProperties properties) {
        if (!StringUtils.hasText(properties.botToken())) {
            // Падаем на старте, а не на первом же деплое, когда отсутствие уведомления —
            // ровно то, чего никто не замечает.
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
