package com.example.inframanager.trello;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;

/**
 * Тот кусок REST API Trello, которым мы пользуемся.
 *
 * <p>Trello аутентифицируется query-параметрами {@code key} и {@code token}, а не
 * заголовком, поэтому они присутствуют явными аргументами в каждом вызове. Вызывающий
 * ровно один ({@link TrelloSender}), и написать учётные данные открыто лучше, чем
 * прятать их в перехватчике, переписывающем URI.
 */
public interface TrelloClient {

    @GetExchange("/1/boards/{boardId}/lists")
    List<TrelloList> boardLists(@PathVariable String boardId,
                                @RequestParam("key") String key,
                                @RequestParam("token") String token,
                                @RequestParam("filter") String filter);

    @GetExchange("/1/cards/{cardId}")
    TrelloCard card(@PathVariable String cardId,
                    @RequestParam("key") String key,
                    @RequestParam("token") String token);

    @GetExchange("/1/boards/{boardId}/labels")
    List<TrelloLabel> boardLabels(@PathVariable String boardId,
                                  @RequestParam("key") String key,
                                  @RequestParam("token") String token,
                                  @RequestParam("limit") int limit);

    @GetExchange("/1/boards/{boardId}/members")
    List<TrelloMember> boardMembers(@PathVariable String boardId,
                                    @RequestParam("key") String key,
                                    @RequestParam("token") String token);

    @PostExchange("/1/labels")
    TrelloLabel createLabel(@RequestParam("key") String key,
                            @RequestParam("token") String token,
                            @RequestParam("idBoard") String boardId,
                            @RequestParam("name") String name,
                            @RequestParam("color") String color);

    @GetExchange("/1/cards/{cardId}/checklists")
    List<TrelloChecklist> cardChecklists(@PathVariable String cardId,
                                         @RequestParam("key") String key,
                                         @RequestParam("token") String token,
                                         @RequestParam("checkItems") String checkItems,
                                         @RequestParam("checkItem_fields") String checkItemFields,
                                         @RequestParam("fields") String fields);

    @PostExchange("/1/checklists")
    TrelloChecklist createChecklist(@RequestParam("key") String key,
                                    @RequestParam("token") String token,
                                    @RequestParam("idCard") String cardId,
                                    @RequestParam("name") String name);

    @DeleteExchange("/1/checklists/{checklistId}")
    void deleteChecklist(@PathVariable String checklistId,
                         @RequestParam("key") String key,
                         @RequestParam("token") String token);

    @PostExchange("/1/checklists/{checklistId}/checkItems")
    TrelloCheckItem createCheckItem(@PathVariable String checklistId,
                                    @RequestParam("key") String key,
                                    @RequestParam("token") String token,
                                    @RequestParam("name") String name,
                                    @RequestParam("checked") boolean checked,
                                    @RequestParam("pos") String position);

    /** Состояние пункта меняется через карточку, а не через чек-лист — так устроено API. */
    @PutExchange("/1/cards/{cardId}/checkItem/{checkItemId}")
    TrelloCheckItem updateCheckItem(@PathVariable String cardId,
                                    @PathVariable String checkItemId,
                                    @RequestParam("key") String key,
                                    @RequestParam("token") String token,
                                    @RequestParam("state") String state);

    @DeleteExchange("/1/checklists/{checklistId}/checkItems/{checkItemId}")
    void deleteCheckItem(@PathVariable String checklistId,
                         @PathVariable String checkItemId,
                         @RequestParam("key") String key,
                         @RequestParam("token") String token);

    @PutExchange("/1/labels/{labelId}")
    TrelloLabel updateLabel(@PathVariable String labelId,
                            @RequestParam("key") String key,
                            @RequestParam("token") String token,
                            @RequestParam("color") String color);

    @PostExchange("/1/cards")
    TrelloCard createCard(@RequestParam("key") String key,
                          @RequestParam("token") String token,
                          @RequestBody CreateCardRequest request);

    @PutExchange("/1/cards/{cardId}")
    TrelloCard updateCard(@PathVariable String cardId,
                          @RequestParam("key") String key,
                          @RequestParam("token") String token,
                          @RequestBody UpdateCardRequest request);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TrelloList(String id, String name, boolean closed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TrelloCard(String id, String name, String idList, boolean closed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TrelloLabel(String id, String name, String color) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TrelloMember(String id, String username, String fullName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TrelloChecklist(String id, String name, List<TrelloCheckItem> checkItems) {

        public List<TrelloCheckItem> items() {
            return checkItems == null ? List.of() : checkItems;
        }
    }

    /** @param state {@code complete} или {@code incomplete} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TrelloCheckItem(String id, String name, String state) {

        public static final String COMPLETE = "complete";
        public static final String INCOMPLETE = "incomplete";

        public boolean isComplete() {
            return COMPLETE.equalsIgnoreCase(state);
        }
    }

    /** {@code idLabels} и {@code idMembers} перечисляются через запятую — так их ждёт Trello. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CreateCardRequest(String idList, String name, String desc, String pos,
                             String idLabels, String idMembers) {
    }

    /**
     * {@code due} — срок в ISO-8601, {@code dueComplete} — отметка «выполнено». Ставятся
     * только вместе: отметку без срока Trello принимает, но нигде не показывает.
     *
     * @param cover {@link Cover} — поставить обложку, {@link Cover#NONE} — снять, null —
     *              не трогать. Тип {@code Object} потому, что параметр у Trello и правда
     *              разнотипный: объект или пустая строка.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UpdateCardRequest(String idList, String name, String desc, Boolean closed,
                             String idLabels, String idMembers, String due, Boolean dueComplete,
                             Object cover) {

        /** Запрос, который меняет карточке одну лишь обложку. */
        static UpdateCardRequest coverOnly(Object cover) {
            return new UpdateCardRequest(null, null, null, null, null, null, null, null, cover);
        }
    }

    /**
     * Обложка — цветной фон карточки, видный прямо на доске.
     *
     * <p>Ставится только через {@code PUT}: у {@code POST /1/cards} такого параметра нет,
     * поэтому обложку только что созданной карточке приходится досылать вторым запросом.
     *
     * @param size {@code normal} — полоса над заголовком, {@code full} — заливка всей
     *             карточки, поверх которой идёт текст
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Cover(String color, String size) {

        /**
         * «Снять обложку». Trello понимает это как пустое значение параметра, а не как
         * объект: пустой объект она в разное время трактовала по-разному, пустая же
         * строка убирала обложку всегда.
         */
        static final String NONE = "";
    }
}
