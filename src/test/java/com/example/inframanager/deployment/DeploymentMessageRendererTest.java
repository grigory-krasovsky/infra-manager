package com.example.inframanager.deployment;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentMessageRendererTest {

    private final DeploymentMessageRenderer renderer = new DeploymentMessageRenderer();

    @Test
    void successMessageNamesTheProjectAndTheStand() {
        String text = renderer.render(event("SUCCESS", "INFRA", "STAGE", "release-42",
                "1757498400000", "1757498472000", "Manual run by krasovsky"));

        assertThat(text)
                .startsWith("✅ <b>INFRA</b> задеплоен на <b>STAGE</b>")
                .contains("Версия: release-42")
                .contains("Длительность: 1 мин 12 с")
                .contains("Запуск: Manual run by krasovsky");
    }

    @Test
    void failureMessageSaysSoAndCarriesTheStatus() {
        String text = renderer.render(event("FAILED", "INFRA", "PROD", null, null, null, null));

        assertThat(text).isEqualTo("❌ <b>INFRA</b> — деплой на <b>PROD</b> не прошёл (FAILED)");
    }

    @Test
    void valuesFromBambooAreEscapedForTelegramHtml() {
        String text = renderer.render(event("SUCCESS", "A & B <core>", "DEV", null, null, null, null));

        assertThat(text).contains("A &amp; B &lt;core&gt;").doesNotContain("<core>");
    }

    @Test
    void missingTimestampsJustOmitTheDuration() {
        String text = renderer.render(event("SUCCESS", "INFRA", "DEV", "v1", "1757498400000", null, null));

        assertThat(text).doesNotContain("Длительность");
    }

    @Test
    void isoTimestampsAreAcceptedAlongsideEpochMillis() {
        String text = renderer.render(event("SUCCESS", "INFRA", "DEV", null,
                "2026-09-10T10:00:00Z", "2026-09-10T10:00:45Z", null));

        assertThat(text).contains("Длительность: 45 с");
    }

    @Test
    void aLinkFromBambooStaysALinkInsteadOfShowingItsMarkup() {
        // Bamboo кладёт в причину запуска готовый якорь; экранированный целиком, он
        // приезжал в чат как «Child of <a href="...">LIZA-APIP-636</a>».
        String text = renderer.render(event("SUCCESS", "Лиза API", "prod", "release-617", null, null,
                "Child of <a href=\"https://bamboo.local/browse/LIZA-APIP-636\">LIZA-APIP-636</a>"));

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

    private BambooDeploymentEvent event(String status, String project, String environment,
                                        String version, String startedAt, String finishedAt,
                                        String trigger) {
        return new BambooDeploymentEvent(1L, status, "FINISHED", project, null, environment,
                version, startedAt, finishedAt, trigger);
    }
}
