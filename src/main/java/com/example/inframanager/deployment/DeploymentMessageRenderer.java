package com.example.inframanager.deployment;

import java.time.Duration;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Renders the Telegram text for a deployment. Telegram HTML parse mode, so every
 * value that comes from Bamboo has to be escaped -- project names and trigger
 * sentences contain user-typed text.
 */
@Component
public class DeploymentMessageRenderer {

    public String render(BambooDeploymentEvent event) {
        String project = escape(event.projectNameOrUnknown());
        String environment = escape(event.environmentNameOrUnknown());

        StringBuilder text = new StringBuilder();
        if (event.isSuccess()) {
            text.append("✅ <b>").append(project).append("</b> задеплоен на <b>").append(environment).append("</b>");
        } else {
            text.append("❌ <b>").append(project).append("</b> — деплой на <b>").append(environment)
                    .append("</b> не прошёл (").append(escape(event.normalisedStatus())).append(')');
        }

        if (StringUtils.hasText(event.deploymentVersionName())) {
            text.append("\nВерсия: ").append(escape(event.deploymentVersionName()));
        }
        Duration duration = event.duration();
        if (duration != null) {
            text.append("\nДлительность: ").append(formatDuration(duration));
        }
        if (StringUtils.hasText(event.triggerSentence())) {
            text.append("\nЗапуск: ").append(escape(event.triggerSentence()));
        }
        return text.toString();
    }

    static String formatDuration(Duration duration) {
        long totalSeconds = Math.max(0, duration.toSeconds());
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes == 0) {
            return seconds + " с";
        }
        return minutes + " мин " + seconds + " с";
    }

    /** Telegram HTML mode only requires these three to be escaped. */
    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
