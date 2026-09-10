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
 * Translates the list names used in configuration into the opaque ids Trello wants.
 *
 * <p>Cached because every card move would otherwise cost an extra call against a
 * rate-limited API, and boards change shape rarely. A miss refetches, so renaming a
 * column is picked up without a restart once the entry expires.
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
     * @throws IllegalStateException if the board has no such list -- a configuration
     *                               error, surfaced rather than silently ignored
     */
    public String listId(String boardId, String listName) {
        String id = lookup(boardId, listName, false);
        if (id != null) {
            return id;
        }
        // Could be a stale cache after someone renamed or added a column.
        id = lookup(boardId, listName, true);
        if (id != null) {
            return id;
        }
        throw new IllegalStateException("Trello board %s has no list named '%s'".formatted(boardId, listName));
    }

    /** Reverse direction, for reporting drift in terms a human recognises. */
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
                        // Two columns with the same name: keep the first, it is the
                        // leftmost and the more likely intent.
                        (first, second) -> first));
        // Kept separately so lookups can be case-insensitive while anything shown to
        // a human still uses the board's actual capitalisation.
        Map<String, String> byId = safe.stream()
                .collect(Collectors.toMap(
                        TrelloClient.TrelloList::id,
                        TrelloClient.TrelloList::name,
                        (first, second) -> first));

        log.debug("Board {} has lists {}", boardId, byId.values());
        return new CachedBoard(byName, byId, Instant.now());
    }

    /** List names are typed by hand in two places; do not let case or padding matter. */
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
