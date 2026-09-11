package com.example.inframanager.trello;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Цвета меток. Интересно ровно одно: две метки не должны оказаться одного цвета,
 * потому что различать их предстоит взглядом.
 */
class TrelloLabelResolverTest {

    private static final String BOARD = "board-1";

    private final TrelloClient client = mock(TrelloClient.class);

    /** Метки, которые «есть на доске»; создание дописывает сюда же. */
    private final List<TrelloClient.TrelloLabel> board = new ArrayList<>();

    @Test
    void aConfiguredLabelIsCreatedInItsConfiguredColour() {
        TrelloLabelResolver resolver = resolver(colours("prod", "red"));

        resolver.labelIds(BOARD, List.of("prod"));

        verify(client).createLabel(anyString(), anyString(), eq(BOARD), eq("prod"), eq("red"));
    }

    @Test
    void labelsWithoutAConfiguredColourDoNotRepeatEachOther() {
        TrelloLabelResolver resolver = resolver(List.of());

        resolver.labelIds(BOARD, List.of("LIZA", "LIZA-API", "ЦСВ"));

        assertThat(board).extracting(TrelloClient.TrelloLabel::color).doesNotHaveDuplicates();
    }

    @Test
    void aNewLabelAvoidsColoursAlreadyTakenOnTheBoard() {
        given(new TrelloClient.TrelloLabel("id-green", "test", "green"));
        TrelloLabelResolver resolver = resolver(List.of());

        resolver.labelIds(BOARD, List.of("release"));

        assertThat(board).filteredOn(label -> label.name().equals("release"))
                .singleElement()
                .extracting(TrelloClient.TrelloLabel::color)
                .isNotEqualTo("green");
    }

    @Test
    void aLabelThatPredatesTheSettingIsRepaintedToMatchIt() {
        // Иначе метки, созданные до настройки цветов, остались бы лаймовыми навсегда:
        // цвет назначается при создании, а создаётся метка один раз.
        given(new TrelloClient.TrelloLabel("id-prod", "prod", "lime"));
        TrelloLabelResolver resolver = resolver(colours("prod", "red"));

        resolver.labelIds(BOARD, List.of("prod"));

        verify(client).updateLabel(eq("id-prod"), anyString(), anyString(), eq("red"));
    }

    @Test
    void aLabelAlreadyInTheRightColourIsLeftAlone() {
        given(new TrelloClient.TrelloLabel("id-prod", "prod", "red"));
        TrelloLabelResolver resolver = resolver(colours("prod", "red"));

        resolver.labelIds(BOARD, List.of("prod"));

        verify(client, never()).updateLabel(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aColourOutsideTheTrelloPaletteIsRejectedAtStartup() {
        assertThatThrownBy(() -> new TrelloLabelResolver(client, properties(colours("prod", "crimson"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("crimson");
    }

    @Test
    void aFullPaletteStillProducesALabel() {
        TrelloLabelResolver.COLORS.forEach(colour ->
                given(new TrelloClient.TrelloLabel("id-" + colour, "branch-" + colour, colour)));
        TrelloLabelResolver resolver = resolver(List.of());

        assertThat(resolver.labelIds(BOARD, List.of("one-branch-too-many"))).hasSize(1);
        assertThat(board).last().extracting(TrelloClient.TrelloLabel::color)
                .isEqualTo(TrelloLabelResolver.hashedColor("one-branch-too-many"));
    }

    private void given(TrelloClient.TrelloLabel label) {
        board.add(label);
    }

    private TrelloLabelResolver resolver(List<TrelloProperties.LabelColor> colours) {
        when(client.boardLabels(eq(BOARD), anyString(), anyString(), anyInt()))
                .thenAnswer(invocation -> List.copyOf(board));
        when(client.createLabel(anyString(), anyString(), eq(BOARD), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    TrelloClient.TrelloLabel created = new TrelloClient.TrelloLabel(
                            "id-" + invocation.<String>getArgument(3),
                            invocation.getArgument(3), invocation.getArgument(4));
                    board.add(created);
                    return created;
                });
        when(client.updateLabel(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String id = invocation.getArgument(0);
                    String colour = invocation.getArgument(3);
                    board.replaceAll(label -> label.id().equals(id)
                            ? new TrelloClient.TrelloLabel(id, label.name(), colour)
                            : label);
                    return null;
                });
        return new TrelloLabelResolver(client, properties(colours));
    }

    private static List<TrelloProperties.LabelColor> colours(String label, String colour) {
        return List.of(new TrelloProperties.LabelColor(label, colour));
    }

    private static TrelloProperties properties(List<TrelloProperties.LabelColor> colours) {
        return new TrelloProperties(true, "https://api.trello.com", "key", "token",
                Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofMinutes(10), Map.of(), colours,
                new TrelloProperties.Reconciliation(false, Duration.ofMinutes(15),
                        TrelloProperties.Reconciliation.OnDrift.LOG,
                        TrelloProperties.Reconciliation.OnMissing.LOG),
                new TrelloProperties.Completion(false, Duration.ofHours(24), Duration.ofDays(7)));
    }
}
