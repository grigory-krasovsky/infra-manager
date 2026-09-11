package com.example.inframanager.notify;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.http.HttpClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(TelegramClientConfig.class);

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
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient());
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

    /**
     * Клиент, которым ходим в Telegram, — при необходимости через прокси.
     *
     * <p>Прокси задаётся здесь, а не системными свойствами JVM, потому что уводить
     * туда надо один только Telegram: Bitbucket и Bamboo находятся во внутренней сети,
     * и через внешний прокси они недостижимы.
     */
    HttpClient httpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout());

        TelegramProperties.Proxy proxy = properties.proxy();
        if (!proxy.isConfigured()) {
            return builder.build();
        }

        // Не резолвим адрес на старте: контейнер может подняться раньше того, что
        // раздаёт имя прокси.
        builder.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(proxy.host(), proxy.port())));
        if (proxy.needsAuthentication()) {
            // К https идём через CONNECT-туннель, а для него JDK по умолчанию
            // выключает Basic — без этого свойства пароль до прокси не доедет.
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
            builder.authenticator(proxyAuthenticator(proxy));
        }
        log.info("Telegram calls go through proxy {}:{}{}", proxy.host(), proxy.port(),
                proxy.needsAuthentication() ? " as " + proxy.username() : "");
        return builder.build();
    }

    /** Отвечает только прокси: учётные данные самого Telegram — это токен бота, а не пароль. */
    private static Authenticator proxyAuthenticator(TelegramProperties.Proxy proxy) {
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY) {
                    return null;
                }
                String password = proxy.password() == null ? "" : proxy.password();
                return new PasswordAuthentication(proxy.username(), password.toCharArray());
            }
        };
    }
}
