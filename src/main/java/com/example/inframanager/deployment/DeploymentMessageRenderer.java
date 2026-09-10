package com.example.inframanager.deployment;

import java.time.Duration;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Рендерит текст для Telegram по одному деплою. Режим разбора — HTML, поэтому любое
 * значение, пришедшее из Bamboo, нужно экранировать: имена проектов и описания запуска
 * содержат набранный человеком текст.
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

    /** В HTML-режиме Telegram экранировать нужно только эти три символа. */
    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
