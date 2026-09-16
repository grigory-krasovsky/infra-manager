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

    /** Заголовок коммита бывает и на абзац; в списке из него читают начало. */
    private static final int MAX_COMMIT_LINE = 120;

    /** Дальше цитату уже не листают, а место она занимает. */
    private static final int MAX_COMMITS = 50;

    /** Предел {@code sendMessage}: 4096 символов на сообщение. */
    private static final int MESSAGE_LIMIT = 4096;

    /** Место под «…и ещё N» с запасом на любое N. */
    private static final int TAIL_BUDGET = 24;

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

    public String render(BambooDeploymentEvent event, DeploymentSubject subject) {
        DeploymentSubject named = subject == null ? DeploymentSubject.empty() : subject;
        String project = escape(event.projectNameOrUnknown());
        String environment = escape(event.environmentNameOrUnknown());

        StringBuilder text = new StringBuilder();
        if (event.isSuccess()) {
            text.append("✅ <b>").append(project).append("</b> задеплоен на <b>").append(environment).append("</b>");
        } else {
            text.append("❌ <b>").append(project).append("</b> — деплой на <b>").append(environment)
                    .append("</b> не прошёл (").append(escape(event.normalisedStatus())).append(')');
        }

        for (DeploymentIssue issue : named.issues()) {
            text.append("\nЗадача: ").append(renderIssue(issue));
        }
        // Пул-реквест — замена задаче, а не добавка к ней: enricher и ищет его только
        // тогда, когда задачи не нашлось.
        for (DeploymentPullRequest pullRequest : named.pullRequests()) {
            text.append("\nПул-реквест: ").append(renderPullRequest(pullRequest));
        }
        // Имя версии Bamboo придумывает сам («release-617»), и о содержимом деплоя оно не
        // говорит ничего — показываем его только когда выяснить не удалось ничего другого.
        if (named.isEmpty() && StringUtils.hasText(event.deploymentVersionName())) {
            text.append("\nВерсия: ").append(escape(event.deploymentVersionName()));
        }

        String when = renderWhen(event);
        if (when != null) {
            text.append("\nКогда: ").append(when);
        }
        if (StringUtils.hasText(event.triggerSentence())) {
            text.append("\nЗапуск: ").append(renderTrigger(event.triggerSentence()));
        }
        // Последним: цитата длинная, и всё, что после неё, читатель бы искал под ней.
        appendCommits(text, named.commits());
        return text.toString();
    }

    /**
     * Сборка, упавшая раньше релиза: деплой в этом случае не запускался вовсе, и объявить
     * о провале, кроме как по самой сборке, больше не от кого.
     */
    public String renderBuildFailure(BambooBuildEvent event) {
        String project = escape(event.projectNameOrUnknown());
        String environment = escape(event.environmentNameOrUnknown());

        StringBuilder text = new StringBuilder()
                .append("❌ <b>").append(project).append("</b> — сборка для <b>").append(environment)
                .append("</b> не прошла");

        String when = renderWhen(event.finishedInstant(), event.duration());
        if (when != null) {
            text.append("\nКогда: ").append(when);
        }
        if (StringUtils.hasText(event.resultUrl())) {
            text.append("\nСборка: <a href=\"").append(escape(event.resultUrl())).append("\">")
                    .append(escape(event.buildResultKey())).append("</a>");
        } else {
            text.append("\nСборка: ").append(escape(event.buildResultKey()));
        }
        if (StringUtils.hasText(event.buildReason())) {
            text.append("\nЗапуск: ").append(renderTrigger(event.buildReason()));
        }
        return text.toString();
    }

    private String renderIssue(DeploymentIssue issue) {
        String key = escape(issue.key());
        String link = StringUtils.hasText(issue.url())
                ? "<a href=\"%s\">%s</a>".formatted(escape(issue.url()), key)
                : key;
        return StringUtils.hasText(issue.summary())
                ? link + " · " + escape(truncate(issue.summary(), MAX_SUMMARY))
                : link;
    }

    private String renderPullRequest(DeploymentPullRequest pullRequest) {
        String number = "#" + pullRequest.id();
        String link = StringUtils.hasText(pullRequest.url())
                ? "<a href=\"%s\">%s</a>".formatted(escape(pullRequest.url()), number)
                : number;
        return StringUtils.hasText(pullRequest.title())
                ? link + " · " + escape(truncate(pullRequest.title(), MAX_SUMMARY))
                : link;
    }

    /**
     * Дописывает коммиты раскрывающейся цитатой — {@code <blockquote expandable>} из
     * Bot API 7.4. Свёрнутой у неё видно несколько первых строк, остальное Telegram
     * прячет под «Показать полностью»: коммитов в деплое бывает и полсотни, и ленту
     * они бы собой заняли целиком.
     *
     * <p>Больше {@value #MESSAGE_LIMIT} символов Telegram не принимает — отвечает 400, и
     * уведомление не доходит вовсе, — поэтому лишние коммиты не показываем, а считаем.
     * Меряем длину вместе с разметкой, хотя Telegram считает только текст (теги не в
     * счёт, «&amp;amp;» — один символ): ошибка тогда всегда в запас.
     */
    private static void appendCommits(StringBuilder text, List<String> commits) {
        if (commits.isEmpty()) {
            return;
        }
        String header = "\nКоммиты (%d):\n<blockquote expandable>".formatted(commits.size());
        int budget = MESSAGE_LIMIT - text.length() - header.length() - "</blockquote>".length() - TAIL_BUDGET;

        StringBuilder quote = new StringBuilder();
        int shown = 0;
        for (String commit : commits) {
            if (shown == MAX_COMMITS) {
                break;
            }
            String line = (shown == 0 ? "• " : "\n• ") + escape(truncate(commit, MAX_COMMIT_LINE));
            if (quote.length() + line.length() > budget) {
                break;
            }
            quote.append(line);
            shown++;
        }
        if (shown == 0) {
            // Места не осталось даже на один коммит; пустую цитату Telegram не примет.
            return;
        }
        if (shown < commits.size()) {
            quote.append("\n…и ещё ").append(commits.size() - shown);
        }
        text.append(header).append(quote).append("</blockquote>");
    }

    /**
     * Когда деплой закончился и сколько шёл. Время — в часовом поясе из настроек: хранится
     * всё в UTC, а читают сообщение люди, живущие в одном поясе.
     */
    private String renderWhen(BambooDeploymentEvent event) {
        return renderWhen(event.finishedInstant(), event.duration());
    }

    private String renderWhen(Instant finished, Duration duration) {
        if (finished == null) {
            return duration == null ? null : "за " + formatDuration(duration);
        }
        String at = WHEN.format(finished.atZone(zone));
        return duration == null ? at : at + ", за " + formatDuration(duration);
    }

    /** Режем до экранирования: в экранированном тексте обрез пришёлся бы внутрь «&amp;amp;». */
    static String truncate(String value, int max) {
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 1).trim() + "…";
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
