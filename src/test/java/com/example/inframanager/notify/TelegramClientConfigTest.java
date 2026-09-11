package com.example.inframanager.notify;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Прокси для Telegram. Проверяется не «дошло ли сообщение» — для этого нужен живой
 * прокси, — а то, что клиент собран именно так, как задумано, и что прокси не
 * распространяется на остальные интеграции.
 */
class TelegramClientConfigTest {

    @Test
    void withoutAProxyTelegramIsDialledDirectly() {
        assertThat(config(proxy(null, 0)).httpClient().proxy()).isEmpty();
    }

    @Test
    void aHostWithoutAPortIsNotAProxy() {
        // Половина настройки — это опечатка, а не намерение ходить куда-то ещё.
        assertThat(config(proxy("host.docker.internal", 0)).httpClient().proxy()).isEmpty();
    }

    @Test
    void aConfiguredProxyIsUsedForTelegram() {
        var selector = config(proxy("host.docker.internal", 8081)).httpClient().proxy();

        assertThat(selector).isPresent();
        assertThat(selector.get().select(URI.create("https://api.telegram.org/botX/sendMessage")))
                .singleElement()
                .satisfies(chosen -> {
                    assertThat(chosen.type()).isEqualTo(Proxy.Type.HTTP);
                    assertThat((InetSocketAddress) chosen.address())
                            .returns("host.docker.internal", InetSocketAddress::getHostString)
                            .returns(8081, InetSocketAddress::getPort);
                });
    }

    @Test
    void aMissingBotTokenStillFailsAtStartup() {
        assertThatThrownBy(() -> new TelegramClientConfig(new TelegramProperties(
                true, "https://api.telegram.org", "  ", Duration.ofSeconds(5), Duration.ofSeconds(10),
                proxy(null, 0), List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TELEGRAM_BOT_TOKEN");
    }

    private static TelegramClientConfig config(TelegramProperties.Proxy proxy) {
        return new TelegramClientConfig(new TelegramProperties(
                true, "https://api.telegram.org", "bot-token",
                Duration.ofSeconds(5), Duration.ofSeconds(10), proxy, List.of()));
    }

    private static TelegramProperties.Proxy proxy(String host, int port) {
        return new TelegramProperties.Proxy(host, port, null, null);
    }
}
