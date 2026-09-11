package com.example.inframanager.notify;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import com.example.inframanager.notify.TelegramProperties.Proxy.Type;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

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
        assertThat(config(proxy(Type.HTTP, null, 0)).httpClient().proxy()).isEmpty();
    }

    @Test
    void aHostWithoutAPortIsNotAProxy() {
        // Половина настройки — это опечатка, а не намерение ходить куда-то ещё.
        assertThat(config(proxy(Type.HTTP, "host.docker.internal", 0)).httpClient().proxy()).isEmpty();
    }

    @Test
    void anHttpProxyIsUsedForTelegram() {
        var selector = config(proxy(Type.HTTP, "host.docker.internal", 8081)).httpClient().proxy();

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
    void socksSwitchesToTheClientThatCanActuallySpeakIt() {
        // java.net.http.HttpClient не умеет SOCKS вовсе, поэтому под него берётся
        // фабрика поверх HttpURLConnection — молча остаться на первой значило бы
        // ходить мимо прокси.
        assertThat(config(proxy(Type.SOCKS5, "127.0.0.1", 1080)).requestFactory())
                .isInstanceOf(SimpleClientHttpRequestFactory.class);
    }

    @Test
    void httpKeepsTheOrdinaryClient() {
        assertThat(config(proxy(Type.HTTP, "host.docker.internal", 8081)).requestFactory())
                .isInstanceOf(JdkClientHttpRequestFactory.class);
    }

    @Test
    void aSocksHostWithoutAPortIsIgnoredLikeAnyOther() {
        assertThat(config(proxy(Type.SOCKS5, "127.0.0.1", 0)).requestFactory())
                .isInstanceOf(JdkClientHttpRequestFactory.class);
    }

    @Test
    void aMissingBotTokenStillFailsAtStartup() {
        assertThatThrownBy(() -> new TelegramClientConfig(new TelegramProperties(
                true, "https://api.telegram.org", "  ", Duration.ofSeconds(5), Duration.ofSeconds(10),
                proxy(Type.HTTP, null, 0), "Europe/Moscow", List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TELEGRAM_BOT_TOKEN");
    }

    private static TelegramClientConfig config(TelegramProperties.Proxy proxy) {
        return new TelegramClientConfig(new TelegramProperties(
                true, "https://api.telegram.org", "bot-token",
                Duration.ofSeconds(5), Duration.ofSeconds(10), proxy, "Europe/Moscow", List.of()));
    }

    private static TelegramProperties.Proxy proxy(Type type, String host, int port) {
        return new TelegramProperties.Proxy(type, host, port, null, null);
    }
}
