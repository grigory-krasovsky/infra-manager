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
 * The slice of the Trello REST API we use.
 *
 * <p>Trello authenticates with {@code key} and {@code token} query parameters, not a
 * header, so they are explicit arguments on every call. There is exactly one caller
 * ({@link TrelloSender}), and spelling the credentials out beats hiding them in a
 * URI-rewriting interceptor.
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CreateCardRequest(String idList, String name, String desc, String pos) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UpdateCardRequest(String idList, String name, String desc, Boolean closed) {
    }
}
