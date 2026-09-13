package com.example.inframanager.deployment;

import java.time.Duration;
import java.util.List;

import com.example.inframanager.notify.TelegramProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentMessageRendererTest {

    private final DeploymentMessageRenderer renderer = new DeploymentMessageRenderer(properties("Europe/Moscow"));

    @Test
    void successMessageNamesTheProjectAndTheStand() {
        String text = renderer.render(event("SUCCESS", "INFRA", "STAGE", "release-42",
                "1757498400000", "1757498472000", "Manual run by krasovsky"), DeploymentSubject.empty());

        assertThat(text)
                .startsWith("✅ <b>INFRA</b> задеплоен на <b>STAGE</b>")
                .contains("Версия: release-42")
                .contains("за 1 мин 12 с")
                .contains("Запуск: Manual run by krasovsky");
    }

    @Test
    void failureMessageSaysSoAndCarriesTheStatus() {
        String text = renderer.render(event("FAILED", "INFRA", "PROD", null, null, null, null), DeploymentSubject.empty());

        assertThat(text).isEqualTo("❌ <b>INFRA</b> — деплой на <b>PROD</b> не прошёл (FAILED)");
    }

    @Test
    void valuesFromBambooAreEscapedForTelegramHtml() {
        String text = renderer.render(event("SUCCESS", "A & B <core>", "DEV", null, null, null, null), DeploymentSubject.empty());

        assertThat(text).contains("A &amp; B &lt;core&gt;").doesNotContain("<core>");
    }

    @Test
    void theIssueIsNamedWithALinkAndItsSummary() {
        String text = renderer.render(event("SUCCESS", "Лиза API", "prod", "release-617", null, null, null),
                issues(new DeploymentIssue("LIZA-599", "Реализация изменения тэга",
                        "https://jira.local/browse/LIZA-599")));

        assertThat(text).contains(
                "Задача: <a href=\"https://jira.local/browse/LIZA-599\">LIZA-599</a> · Реализация изменения тэга");
    }

    @Test
    void thePullRequestIsNamedWhenNoIssueIsKnown() {
        // Деплои Лизы приходят без задачи: ключа в коммитах нет. Пул-реквест тогда —
        // единственное, что говорит, что именно уехало.
        String text = renderer.render(event("SUCCESS", "Лиза", "prod", "release-423", null, null, null),
                pullRequests(new DeploymentPullRequest(107, "2026 07 31 init claude code",
                        "https://bitbucket.local/projects/LIZA/repos/liza/pull-requests/107")));

        assertThat(text).contains("Пул-реквест: <a href=\"https://bitbucket.local/projects/LIZA/repos/liza"
                + "/pull-requests/107\">#107</a> · 2026 07 31 init claude code");
    }

    @Test
    void aPullRequestWithoutAKnownBitbucketAddressKeepsItsNumber() {
        String text = renderer.render(event("SUCCESS", "Лиза", "prod", null, null, null, null),
                pullRequests(new DeploymentPullRequest(107, "init claude code", null)));

        assertThat(text).contains("Пул-реквест: #107 · init claude code").doesNotContain("<a href");
    }

    @Test
    void theVersionAppearsOnlyWhenNothingBetterIsKnown() {
        // «release-617» Bamboo придумывает сам, и о содержимом деплоя оно не говорит
        // ничего — показывать его рядом с задачей значит тратить строку впустую.
        String withIssue = renderer.render(event("SUCCESS", "Лиза API", "prod", "release-617", null, null, null),
                issues(new DeploymentIssue("LIZA-599", null, "https://jira.local/browse/LIZA-599")));
        String withPullRequest = renderer.render(
                event("SUCCESS", "Лиза API", "prod", "release-617", null, null, null),
                pullRequests(new DeploymentPullRequest(172, "init claude code", null)));
        String without = renderer.render(event("SUCCESS", "Лиза API", "prod", "release-617", null, null, null),
                DeploymentSubject.empty());

        assertThat(withIssue).doesNotContain("release-617");
        assertThat(withPullRequest).doesNotContain("release-617");
        assertThat(without).contains("Версия: release-617");
    }

    @Test
    void anIssueWithoutAKnownJiraAddressStaysPlainText() {
        String text = renderer.render(event("SUCCESS", "ОрВД", "dev", null, null, null, null),
                issues(new DeploymentIssue("ORVD-1657", "Фильтр ЭДО", null)));

        assertThat(text).contains("Задача: ORVD-1657 · Фильтр ЭДО").doesNotContain("<a href");
    }

    @Test
    void theFinishTimeIsShownInTheConfiguredZone() {
        // 1757498472000 — это 10.09.2025 10:01 UTC, то есть 13:01 в Москве.
        String moscow = renderer.render(event("SUCCESS", "INFRA", "DEV", null,
                "1757498400000", "1757498472000", null), DeploymentSubject.empty());
        String utc = new DeploymentMessageRenderer(properties("UTC")).render(event("SUCCESS", "INFRA", "DEV", null,
                "1757498400000", "1757498472000", null), DeploymentSubject.empty());

        assertThat(moscow).contains("Когда: 10.09.2025 13:01, за 1 мин 12 с");
        assertThat(utc).contains("Когда: 10.09.2025 10:01, за 1 мин 12 с");
    }

    @Test
    void aNonsenseTimeZoneIsRefusedAtStartup() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new DeploymentMessageRenderer(properties("Middle/Earth"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Middle/Earth");
    }

    @Test
    void aLongSummaryIsCutRatherThanWrapped() {
        String summary = "Реализация изменения тэга и добавление чекбокса Полёт по позывному по полёту в отказе "
                + "и ещё немного слов";
        String text = renderer.render(event("SUCCESS", "Лиза", "prod", null, null, null, null),
                issues(new DeploymentIssue("LIZA-599", summary, "https://jira.local/browse/LIZA-599")));

        assertThat(text).contains("…").doesNotContain("ещё немного слов");
    }

    @Test
    void aLongPullRequestTitleIsCutTheSameWay() {
        String title = "2026 07 31 init claude code и ещё десяток слов, которые автор счёл нужным "
                + "уместить в заголовок, а мы не уместим";
        String text = renderer.render(event("SUCCESS", "Лиза", "prod", null, null, null, null),
                pullRequests(new DeploymentPullRequest(107, title, null)));

        assertThat(text).contains("…").doesNotContain("не уместим");
    }

    @Test
    void missingTimestampsJustOmitTheDuration() {
        String text = renderer.render(event("SUCCESS", "INFRA", "DEV", "v1", "1757498400000", null, null),
                DeploymentSubject.empty());

        assertThat(text).doesNotContain("за ");
    }

    @Test
    void isoTimestampsAreAcceptedAlongsideEpochMillis() {
        String text = renderer.render(event("SUCCESS", "INFRA", "DEV", null,
                "2026-09-10T10:00:00Z", "2026-09-10T10:00:45Z", null), DeploymentSubject.empty());

        assertThat(text).contains("за 45 с");
    }

    @Test
    void aLinkFromBambooStaysALinkInsteadOfShowingItsMarkup() {
        // Bamboo кладёт в причину запуска готовый якорь; экранированный целиком, он
        // приезжал в чат как «Child of <a href="...">LIZA-APIP-636</a>».
        String text = renderer.render(event("SUCCESS", "Лиза API", "prod", "release-617", null, null,
                "Child of <a href=\"https://bamboo.local/browse/LIZA-APIP-636\">LIZA-APIP-636</a>"), DeploymentSubject.empty());

        assertThat(text).contains(
                "Запуск: Child of <a href=\"https://bamboo.local/browse/LIZA-APIP-636\">LIZA-APIP-636</a>");
    }

    @Test
    void textAroundTheLinkIsStillEscaped() {
        assertThat(DeploymentMessageRenderer.renderTrigger(
                "A & B <a href=\"https://bamboo.local/x\">K&Y</a> <b>bold</b>"))
                .isEqualTo("A &amp; B <a href=\"https://bamboo.local/x\">K&amp;Y</a> bold");
    }

    @Test
    void anHrefThatIsNotAnHttpUrlKeepsOnlyTheLabel() {
        // Подставить в href что угодно — значит отдать Telegram разметку, которой мы
        // не управляем; в худшем случае он ответит 400, и уведомление не дойдёт.
        assertThat(DeploymentMessageRenderer.renderTrigger(
                "Child of <a href=\"javascript:alert(1)\">KEY-1</a>"))
                .isEqualTo("Child of KEY-1");
    }

    @Test
    void aPlainSentenceIsUnchangedApartFromEscaping() {
        assertThat(DeploymentMessageRenderer.renderTrigger("Manual run by krasovsky"))
                .isEqualTo("Manual run by krasovsky");
        assertThat(DeploymentMessageRenderer.renderTrigger("a < b & c"))
                .isEqualTo("a &lt; b &amp; c");
    }

    @Test
    void subMinuteDurationsDropTheMinutesPart() {
        assertThat(DeploymentMessageRenderer.formatDuration(Duration.ofSeconds(9))).isEqualTo("9 с");
        assertThat(DeploymentMessageRenderer.formatDuration(Duration.ofSeconds(60))).isEqualTo("1 мин 0 с");
    }

    private static DeploymentSubject issues(DeploymentIssue... issues) {
        return new DeploymentSubject(List.of(issues), List.of());
    }

    private static DeploymentSubject pullRequests(DeploymentPullRequest... pullRequests) {
        return new DeploymentSubject(List.of(), List.of(pullRequests));
    }

    private static TelegramProperties properties(String zone) {
        return new TelegramProperties(true, "https://api.telegram.org", "token",
                Duration.ofSeconds(5), Duration.ofSeconds(10),
                new TelegramProperties.Proxy(TelegramProperties.Proxy.Type.HTTP, null, 0, null, null),
                zone, List.of());
    }

    private BambooDeploymentEvent event(String status, String project, String environment,
                                        String version, String startedAt, String finishedAt,
                                        String trigger) {
        return new BambooDeploymentEvent(1L, status, "FINISHED", project, null, environment,
                version, startedAt, finishedAt, trigger);
    }
}
