package com.example.inframanager.pullrequest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch-derived pull request titles repeat what the card already shows: the date,
 * and the issue key that is about to go in brackets. These are the real titles from
 * the repositories this mirrors.
 */
class PrCardContentRendererTest {

    @Test
    void stripsTheLeadingDateAndTheIssueKey() {
        assertThat(PrCardContentRenderer.cleanTitle(
                "2026 08 19 ORVD-1047 BusinessProcess Copy Test", "ORVD-1047"))
                .isEqualTo("BusinessProcess Copy Test");
    }

    @Test
    void stripsAKeyThatLeadsWithoutADate() {
        assertThat(PrCardContentRenderer.cleanTitle("ORVD-1135 Duplicates Emails", "ORVD-1135"))
                .isEqualTo("Duplicates Emails");
    }

    @Test
    void stripsADateWhenThereIsNoKey() {
        assertThat(PrCardContentRenderer.cleanTitle("2026 07 29 claude setup", null))
                .isEqualTo("claude setup");
    }

    @Test
    void acceptsDashedAndDottedDates() {
        assertThat(PrCardContentRenderer.cleanTitle("2026-07-14 LIZA-617 track changes", "LIZA-617"))
                .isEqualTo("track changes");
        assertThat(PrCardContentRenderer.cleanTitle("19.08.2026 fix", null)).isEqualTo("fix");
    }

    @Test
    void removesTheKeyWhereverItAppears() {
        assertThat(PrCardContentRenderer.cleanTitle("fix for ORVD-1047 in copier", "ORVD-1047"))
                .isEqualTo("fix for in copier");
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertThat(PrCardContentRenderer.cleanTitle("orvd-1047 lowercase branch", "ORVD-1047"))
                .isEqualTo("lowercase branch");
    }

    @Test
    void leavesAnAlreadyCleanTitleAlone() {
        assertThat(PrCardContentRenderer.cleanTitle("Duplicates Emails", null))
                .isEqualTo("Duplicates Emails");
    }

    @Test
    void keepsTheOriginalWhenStrippingWouldLeaveNothing() {
        // A title that is only a date and a key would otherwise produce a nameless card.
        assertThat(PrCardContentRenderer.cleanTitle("2026 08 19 ORVD-1047", "ORVD-1047"))
                .isEqualTo("2026 08 19 ORVD-1047");
    }
}
