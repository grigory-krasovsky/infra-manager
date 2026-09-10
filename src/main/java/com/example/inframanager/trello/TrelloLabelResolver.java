package com.example.inframanager.trello;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns label names into the ids Trello wants, creating any that do not exist yet.
 *
 * <p>Created rather than required up front because the values are discovered, not
 * configured: a new target branch or a new contributor should appear on the board
 * without anyone editing configuration first.
 *
 * <p>Colour is derived from the name, so the same branch keeps the same colour
 * across boards and restarts instead of depending on creation order.
 */
public class TrelloLabelResolver {

    private static final Logger log = LoggerFactory.getLogger(TrelloLabelResolver.class);

    /** Trello's label palette. Order matters only in that it must stay stable. */
    private static final List<String> COLORS = List.of(
            "green", "yellow", "orange", "red", "purple", "blue", "sky", "lime", "pink", "black");

    private static final int LABEL_FETCH_LIMIT = 1000;

    private final TrelloClient client;
    private final TrelloProperties properties;
    private final Map<String, CachedLabels> cache = new ConcurrentHashMap<>();

    public TrelloLabelResolver(TrelloClient client, TrelloProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * @param names label names, in display form; blanks and duplicates are dropped
     * @return ids of the corresponding labels, creating any that are missing
     */
    public List<String> labelIds(String boardId, List<String> names) {
        Set<String> wanted = new LinkedHashSet<>(names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .toList());
        if (wanted.isEmpty()) {
            return List.of();
        }

        Map<String, String> byName = labels(boardId, false);
        List<String> ids = new java.util.ArrayList<>();
        for (String name : wanted) {
            String id = byName.get(normalise(name));
            if (id == null) {
                // Could be a label somebody added since the cache was filled.
                byName = labels(boardId, true);
                id = byName.get(normalise(name));
            }
            if (id == null) {
                id = create(boardId, name);
            }
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    public void invalidate(String boardId) {
        cache.remove(boardId);
    }

    private String create(String boardId, String name) {
        try {
            TrelloClient.TrelloLabel created = client.createLabel(
                    properties.key(), properties.token(), boardId, name, colorFor(name));
            log.info("Created Trello label '{}' ({}) on board {}", name, created.color(), boardId);
            invalidate(boardId);
            return created.id();
        } catch (Exception e) {
            // A missing label is cosmetic; failing the card over it would be worse.
            log.warn("Could not create Trello label '{}' on board {}", name, boardId, e);
            return null;
        }
    }

    private Map<String, String> labels(String boardId, boolean forceRefresh) {
        CachedLabels cached = cache.get(boardId);
        if (forceRefresh || cached == null || cached.isExpired(properties.listCacheTtl())) {
            cached = fetch(boardId);
            cache.put(boardId, cached);
        }
        return cached.idsByName();
    }

    private CachedLabels fetch(String boardId) {
        List<TrelloClient.TrelloLabel> labels =
                client.boardLabels(boardId, properties.key(), properties.token(), LABEL_FETCH_LIMIT);
        Map<String, String> byName = labels == null ? Map.of() : labels.stream()
                .filter(label -> label.name() != null && !label.name().isBlank())
                .collect(Collectors.toMap(
                        label -> normalise(label.name()),
                        TrelloClient.TrelloLabel::id,
                        (first, second) -> first));
        return new CachedLabels(byName, Instant.now());
    }

    /** Stable across restarts: same name, same colour. */
    static String colorFor(String name) {
        int hash = normalise(name).hashCode();
        return COLORS.get(Math.floorMod(hash, COLORS.size()));
    }

    private static String normalise(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private record CachedLabels(Map<String, String> idsByName, Instant fetchedAt) {

        boolean isExpired(Duration ttl) {
            return fetchedAt.plus(ttl).isBefore(Instant.now());
        }
    }
}
