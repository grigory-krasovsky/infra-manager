package com.example.inframanager.deployment;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.inframanager.notify.TelegramProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Рендерит текст для Telegram по одному деплою. Режим разбора — HTML, поэтому любое
 * значение, пришедшее из Bamboo, нужно экранировать: имена проектов и описания запуска
 * содержат набранный человеком текст.
 */
@Component
public class DeploymentMessageRenderer {

    private static final Pattern ANCHOR = Pattern.compile(
            "<a\\s[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Тег — это «<» и сразу буква: так «a < b» останется текстом, а не исчезнет. */
    private static final Pattern TAG = Pattern.compile("</?[a-zA-Z][^>]*>");

    private static final Pattern HTTP_URL = Pattern.compile("https?://[^\\s\"]+");

    /** Длинное summary задачи превращает уведомление в простыню. */
    private static final int MAX_SUMMARY = 90;

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final ZoneId zone;

    public DeploymentMessageRenderer(TelegramProperties properties) {
        try {
            this.zone = ZoneId.of(properties.timeZone());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(
                    "infra-manager.telegram.time-zone: '%s' is not a time zone".formatted(properties.timeZone()), e);
        }
    }

    public String render(BambooDeploymentEvent event, List<DeploymentIssue> issues) {
        String project = escape(event.projectNameOrUnknown());
        String environment = escape(event.environmentNameOrUnknown());

        StringBuilder text = new StringBuilder();
        if (event.isSuccess()) {
            text.append("✅ <b>").append(project).append("</b> задеплоен на <b>").append(environment).append("</b>");
        } else {
            text.append("❌ <b>").append(project).append("</b> — деплой на <b>").append(environment)
                    .append("</b> не прошёл (").append(escape(event.normalisedStatus())).append(')');
        }

        for (DeploymentIssue issue : issues == null ? List.<DeploymentIssue>of() : issues) {
            text.append("\nЗадача: ").append(renderIssue(issue));
        }
        // Имя версии Bamboo придумывает сам («release-617»), и о содержимом деплоя оно не
        // говорит ничего — показываем его только когда задачу выяснить не удалось.
        if ((issues == null || issues.isEmpty()) && StringUtils.hasText(event.deploymentVersionName())) {
            text.append("\nВерсия: ").append(escape(event.deploymentVersionName()));
        }

        String when = renderWhen(event);
        if (when != null) {
            text.append("\nКогда: ").append(when);
        }
        if (StringUtils.hasText(event.triggerSentence())) {
            text.append("\nЗапуск: ").append(renderTrigger(event.triggerSentence()));
        }
        return text.toString();
    }

    private String renderIssue(DeploymentIssue issue) {
        String key = escape(issue.key());
        String link = StringUtils.hasText(issue.url())
                ? "<a href=\"%s\">%s</a>".formatted(escape(issue.url()), key)
                : key;
        return StringUtils.hasText(issue.summary())
                ? link + " · " + escape(truncate(issue.summary()))
                : link;
    }

    /**
     * Когда деплой закончился и сколько шёл. Время — в часовом поясе из настроек: хранится
     * всё в UTC, а читают сообщение люди, живущие в одном поясе.
     */
    private String renderWhen(BambooDeploymentEvent event) {
        Instant finished = event.finishedInstant();
        Duration duration = event.duration();
        if (finished == null) {
            return duration == null ? null : "за " + formatDuration(duration);
        }
        String at = WHEN.format(finished.atZone(zone));
        return duration == null ? at : at + ", за " + formatDuration(duration);
    }

    static String truncate(String summary) {
        String trimmed = summary.trim();
        return trimmed.length() <= MAX_SUMMARY ? trimmed : trimmed.substring(0, MAX_SUMMARY - 1).trim() + "…";
    }

    /**
     * Причина запуска приходит из Bamboo уже размеченной: {@code Child of <a href="…">KEY</a>}.
     *
     * <p>Экранировать её целиком, как остальные значения, — значит показать человеку теги
     * в виде текста. Пропустить как есть тоже нельзя: Telegram отвечает 400 на первый же
     * неизвестный тег, и уведомление не доходит вовсе. Поэтому ссылку переносим своей
     * разметкой, остальные теги выбрасываем, а текст между ними экранируем.
     */
    static String renderTrigger(String sentence) {
        StringBuilder out = new StringBuilder();
        Matcher anchor = ANCHOR.matcher(sentence);
        int plainFrom = 0;
        while (anchor.find()) {
            out.append(plain(sentence.substring(plainFrom, anchor.start())));
            String href = anchor.group(1);
            String label = plain(anchor.group(2));
            // Ссылку строим сами и только из http(s): подставить в href что угодно
            // означало бы отдать Telegram разметку, которой мы не управляем.
            if (HTTP_URL.matcher(href).matches()) {
                out.append("<a href=\"").append(escape(href)).append("\">").append(label).append("</a>");
            } else {
                out.append(label);
            }
            plainFrom = anchor.end();
        }
        return out.append(plain(sentence.substring(plainFrom))).toString();
    }

    private static String plain(String value) {
        return escape(TAG.matcher(value).replaceAll(""));
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
