package com.example.inframanager.notify;

import com.example.inframanager.outbound.OutboundTarget;
import com.example.inframanager.outbound.OutboundTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Разносит одно уведомление по всем настроенным чатам, которым интересно это окружение,
 * ставя в очередь по задаче на чат.
 *
 * <p>Регистрируется независимо от того, включён ли Telegram: при выключенном задачи всё
 * равно попадают в очередь и затем помечаются SKIPPED, так что ошибка настройки видна
 * в {@code outbound_task}, а не приводит к молчаливой потере уведомлений.
 */
@Service
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private final TelegramProperties properties;
    private final OutboundTaskService taskService;
    private final ObjectMapper objectMapper;

    public TelegramNotifier(TelegramProperties properties,
                            OutboundTaskService taskService,
                            ObjectMapper objectMapper) {
        this.properties = properties;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    /**
     * @param environment стенд, о котором речь; маршруты фильтруют именно по нему
     * @param dedupKeyBase должен опознавать сам факт (например, id результата деплоя),
     *                     а не момент отправки, чтобы повторная обработка исходного
     *                     события не отправила вторую копию
     * @return для скольких чатов сообщение впервые поставлено в очередь
     */
    public int notify(String environment, String dedupKeyBase, String text) {
        if (properties.routes().isEmpty()) {
            log.warn("No Telegram routes configured; dropping notification for {}", environment);
            return 0;
        }

        int queued = 0;
        boolean anyRouteMatched = false;
        for (TelegramProperties.Route route : properties.routes()) {
            if (!route.matches(environment)) {
                continue;
            }
            anyRouteMatched = true;
            TelegramMessage message = new TelegramMessage(route.chatId(), route.messageThreadId(), text);
            String dedupKey = "telegram:%s:%s".formatted(dedupKeyBase, route.chatId());
            if (taskService.enqueue(OutboundTarget.TELEGRAM, "sendMessage", dedupKey,
                    objectMapper.writeValueAsString(message))) {
                queued++;
            }
        }

        if (!anyRouteMatched) {
            log.info("No Telegram route matches environment {}; nothing queued", environment);
        }
        return queued;
    }
}
