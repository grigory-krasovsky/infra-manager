package com.example.inframanager.notify;

import com.example.inframanager.outbound.OutboundTarget;
import com.example.inframanager.outbound.OutboundTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Fans one notification out to every configured chat that cares about the
 * environment, queueing a task per chat.
 *
 * <p>Registered whether or not Telegram is enabled: with it disabled the tasks
 * still get queued and then marked SKIPPED, which leaves the misconfiguration
 * visible in {@code outbound_task} rather than silently dropping notifications.
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
     * @param environment the stand this concerns; routes filter on it
     * @param dedupKeyBase must identify the underlying fact (e.g. a deployment result
     *                     id), not the moment of sending, so reprocessing the source
     *                     event does not send a second copy
     * @return how many chats the message was newly queued for
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
