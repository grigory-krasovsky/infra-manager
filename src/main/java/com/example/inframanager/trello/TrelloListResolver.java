package com.example.inframanager.trello;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Переводит имена списков из конфигурации в непрозрачные id, которых требует Trello.
 *
 * <p>Кешируется, потому что иначе каждое перемещение карточки стоило бы лишнего вызова
 * к API с лимитом запросов, а форма досок меняется редко. Промах приводит к повторному
 * запросу, так что переименование колонки подхватывается без перезапуска — как только
 * запись протухнет.
 */
public class TrelloListResolver {

    private static final Logger log = LoggerFactory.getLogger(TrelloListResolver.class);

    private final TrelloClient client;
    private final TrelloProperties properties;
    private final Map<String, CachedBoard> cache = new ConcurrentHashMap<>();

    public TrelloListResolver(TrelloClient client, TrelloProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * @throws IllegalStateException если на доске нет такого списка — это ошибка
     *                               конфигурации, и о ней сообщается, а не молчится
     */
    public String listId(String boardId, String listName) {
        String id = lookup(boardId, listName, false);
        if (id != null) {
            return id;
        }
        // Возможно, кеш устарел после того, как колонку переименовали или добавили.
        id = lookup(boardId, listName, true);
        if (id != null) {
            return id;
        }
        throw new IllegalStateException("Trello board %s has no list named '%s'".formatted(boardId, listName));
    }

    /** Обратное направление — чтобы сообщать о расхождении понятными человеку словами. */
    public Optional<String> listName(String boardId, String listId) {
        if (listId == null) {
            return Optional.empty();
        }
        CachedBoard cached = cache.get(boardId);
        if (cached == null || cached.isExpired(properties.listCacheTtl())) {
            cached = fetch(boardId);
            cache.put(boardId, cached);
        }
        return Optional.ofNullable(cached.namesById().get(listId));
    }

    public void invalidate(String boardId) {
        cache.remove(boardId);
    }

    private String lookup(String boardId, String listName, boolean forceRefresh) {
        CachedBoard cached = cache.get(boardId);
        if (forceRefresh || cached == null || cached.isExpired(properties.listCacheTtl())) {
            cached = fetch(boardId);
            cache.put(boardId, cached);
        }
        return cached.listIdsByName().get(normalise(listName));
    }

    private CachedBoard fetch(String boardId) {
        List<TrelloClient.TrelloList> lists =
                client.boardLists(boardId, properties.key(), properties.token(), "open");
        List<TrelloClient.TrelloList> safe = lists == null ? List.of() : lists;

        Map<String, String> byName = safe.stream()
                .collect(Collectors.toMap(
                        list -> normalise(list.name()),
                        TrelloClient.TrelloList::id,
                        // Две колонки с одинаковым именем: оставляем первую — она левее,
                        // и её, скорее всего, и имели в виду.
                        (first, second) -> first));
        // Хранится отдельно, чтобы поиск был нечувствителен к регистру, а всё показываемое
        // человеку сохраняло написание, принятое на доске.
        Map<String, String> byId = safe.stream()
                .collect(Collectors.toMap(
                        TrelloClient.TrelloList::id,
                        TrelloClient.TrelloList::name,
                        (first, second) -> first));

        log.debug("Board {} has lists {}", boardId, byId.values());
        return new CachedBoard(byName, byId, Instant.now());
    }

    /** Имена списков набираются руками в двух местах; регистр и пробелы не должны иметь значения. */
    private static String normalise(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private record CachedBoard(Map<String, String> listIdsByName,
                               Map<String, String> namesById,
                               Instant fetchedAt) {

        boolean isExpired(Duration ttl) {
            return fetchedAt.plus(ttl).isBefore(Instant.now());
        }
    }
}
