package com.example.inframanager.trello;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Чек-лист задач ревью на карточке. Проверяется сверка: что добавляется, что
 * переставляется галочкой и что снимается, — и что чужие чек-листы остаются целы.
 */
class TrelloChecklistSyncTest {

    private static final String CARD = "card-1";

    private final TrelloClient client = mock(TrelloClient.class);
    private final TrelloChecklistSync sync = new TrelloChecklistSync(client, properties());

    @Test
    void theFirstTasksCreateTheChecklist() {
        givenChecklists();
        when(client.createChecklist(anyString(), anyString(), eq(CARD), anyString()))
                .thenReturn(new TrelloClient.TrelloChecklist("chk-1", TrelloChecklistSync.NAME, List.of()));

        sync.sync(CARD, List.of(item("Заменить List на Set", false), item("Поправить имя файла", true)));

        verify(client).createChecklist(anyString(), anyString(), eq(CARD), eq(TrelloChecklistSync.NAME));
        verify(client).createCheckItem(eq("chk-1"), anyString(), anyString(),
                eq("Заменить List на Set"), eq(false), anyString());
        verify(client).createCheckItem(eq("chk-1"), anyString(), anyString(),
                eq("Поправить имя файла"), eq(true), anyString());
    }

    @Test
    void aResolvedTaskGetsItsTick() {
        givenChecklists(checklist("chk-1", TrelloChecklistSync.NAME,
                checkItem("item-1", "Заменить List на Set", false)));

        sync.sync(CARD, List.of(item("Заменить List на Set", true)));

        verify(client).updateCheckItem(eq(CARD), eq("item-1"), anyString(), anyString(), eq("complete"));
    }

    @Test
    void aReopenedTaskLosesIt() {
        givenChecklists(checklist("chk-1", TrelloChecklistSync.NAME,
                checkItem("item-1", "Заменить List на Set", true)));

        sync.sync(CARD, List.of(item("Заменить List на Set", false)));

        verify(client).updateCheckItem(eq(CARD), eq("item-1"), anyString(), anyString(), eq("incomplete"));
    }

    @Test
    void anUnchangedTaskIsLeftAlone() {
        givenChecklists(checklist("chk-1", TrelloChecklistSync.NAME,
                checkItem("item-1", "Заменить List на Set", false)));

        sync.sync(CARD, List.of(item("Заменить List на Set", false)));

        verify(client, never()).updateCheckItem(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(client, never()).createCheckItem(anyString(), anyString(), anyString(), anyString(),
                anyBoolean(), anyString());
    }

    @Test
    void aDeletedTaskLeavesTheChecklist() {
        givenChecklists(checklist("chk-1", TrelloChecklistSync.NAME,
                checkItem("item-1", "Заменить List на Set", false),
                checkItem("item-2", "Задача, которую удалили", false)));

        sync.sync(CARD, List.of(item("Заменить List на Set", false)));

        verify(client).deleteCheckItem(eq("chk-1"), eq("item-2"), anyString(), anyString());
        verify(client, never()).deleteCheckItem(eq("chk-1"), eq("item-1"), anyString(), anyString());
    }

    @Test
    void whenTheLastTaskIsGoneTheChecklistGoesToo() {
        givenChecklists(checklist("chk-1", TrelloChecklistSync.NAME,
                checkItem("item-1", "Заменить List на Set", true)));

        sync.sync(CARD, List.of());

        verify(client).deleteChecklist(eq("chk-1"), anyString(), anyString());
    }

    @Test
    void aCardThatNeverHadTasksGetsNoEmptyChecklist() {
        givenChecklists();

        sync.sync(CARD, List.of());

        verify(client, never()).createChecklist(anyString(), anyString(), anyString(), anyString());
        verify(client, never()).deleteChecklist(anyString(), anyString(), anyString());
    }

    @Test
    void checklistsMadeByHandAreNotTouched() {
        // Доска принадлежит людям; мы ведём на ней ровно один свой список.
        givenChecklists(checklist("chk-human", "Мой личный список",
                checkItem("item-9", "Не забыть про отпуск", false)));
        when(client.createChecklist(anyString(), anyString(), eq(CARD), anyString()))
                .thenReturn(new TrelloClient.TrelloChecklist("chk-1", TrelloChecklistSync.NAME, List.of()));

        sync.sync(CARD, List.of(item("Заменить List на Set", false)));

        verify(client, never()).deleteChecklist(eq("chk-human"), anyString(), anyString());
        verify(client, never()).deleteCheckItem(anyString(), eq("item-9"), anyString(), anyString());
        verify(client).createChecklist(anyString(), anyString(), eq(CARD), eq(TrelloChecklistSync.NAME));
    }

    @Test
    void tasksWeCouldNotAskAboutLeaveTheChecklistAlone() {
        // null — это «неизвестно», а не «задач нет»: иначе недоступный на минуту
        // Bitbucket стирал бы с карточек все замечания.
        sync.sync(CARD, null);

        verifyNoInteractions(client);
    }

    @Test
    void aBrokenChecklistCallDoesNotBreakTheCard() {
        when(client.cardChecklists(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("trello is down"));

        sync.sync(CARD, List.of(item("Заменить List на Set", false)));

        assertThat(true).isTrue();
    }

    private void givenChecklists(TrelloClient.TrelloChecklist... checklists) {
        when(client.cardChecklists(eq(CARD), anyString(), anyString(), any(), any(), any()))
                .thenReturn(List.of(checklists));
    }

    private static TrelloClient.TrelloChecklist checklist(String id, String name,
                                                          TrelloClient.TrelloCheckItem... items) {
        return new TrelloClient.TrelloChecklist(id, name, List.of(items));
    }

    private static TrelloClient.TrelloCheckItem checkItem(String id, String name, boolean complete) {
        return new TrelloClient.TrelloCheckItem(id, name, complete ? "complete" : "incomplete");
    }

    private static TrelloCardCommand.ChecklistItem item(String name, boolean done) {
        return new TrelloCardCommand.ChecklistItem(name, done);
    }

    private static TrelloProperties properties() {
        return new TrelloProperties(true, "https://api.trello.com", "key", "token",
                Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofMinutes(10),
                Map.of(), List.of(), "normal", false,
                new TrelloProperties.Reconciliation(false, Duration.ofMinutes(15),
                        TrelloProperties.Reconciliation.OnDrift.LOG,
                        TrelloProperties.Reconciliation.OnMissing.LOG),
                new TrelloProperties.Completion(false, Duration.ofHours(24), Duration.ofDays(7)));
    }
}
