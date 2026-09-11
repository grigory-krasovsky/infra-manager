package com.example.inframanager.trello;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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

    /** {@code idLabels} и {@code idMembers} перечисляются через запятую — так их ждёт Trello. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CreateCardRequest(String idList, String name, String desc, String pos,
                             String idLabels, String idMembers) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UpdateCardRequest(String idList, String name, String desc, Boolean closed,
                             String idLabels, String idMembers) {
    }
}
