package com.example.inframanager.trello;

import com.example.inframanager.pullrequest.PullRequestRef;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * То, что лежит в {@code outbound_task.payload} для задачи TRELLO: желаемое состояние
 * одной карточки, а не шаг, который надо выполнить.
 *
 * <p>Описание целевого состояния вместо «создать» или «переместить» делает задачу
 * безопасной для повтора и для обработки не по порядку — применив её дважды, получим
 * ту же карточку в том же списке.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrelloCardCommand(

        PullRequestRef pullRequest,

        String boardId,

        /** Null означает «оставить карточку на месте и лишь обновить её содержимое». */
        String moveToListName,

        /** Куда попадает карточка, которой ещё нет. Никогда не null. */
        String createInListName,

        String title,

        String description,

        /** Записывается в связку, когда известен; null, если в ветке нет ключа. */
        String issueKey,

        /**
         * Имена меток; отсутствующие создаются на доске. Null оставляет метки карточки
         * как есть — именно этого хочет сверка.
         */
        java.util.List<String> labels,

        /**
         * Идентификаторы автора, от наиболее точного к менее точному. Сопоставляются
         * с существующими участниками доски; в отличие от меток, участников создать
         * нельзя, поэтому автор без учётной записи в Trello просто оставляет карточку
         * без исполнителя.
         */
        java.util.List<String> memberCandidates,

        /**
         * Задачи ревью как пункты чек-листа. Null означает «не трогать чек-лист» —
         * именно это нужно сверке и всему, что не знает о задачах; пустой список,
         * наоборот, означает «задач нет», и чек-лист убирается с карточки.
         */
        java.util.List<ChecklistItem> checklist,

        boolean archive) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChecklistItem(String name, boolean done) {
    }
}
