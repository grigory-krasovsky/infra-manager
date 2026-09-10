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
 * Превращает имена меток в id, которых требует Trello, создавая те, которых ещё нет.
 *
 * <p>Создаём, а не требуем заранее, потому что значения обнаруживаются, а не
 * настраиваются: новая целевая ветка или новый участник должны появиться на доске без
 * того, чтобы кто-то сперва правил конфигурацию.
 *
 * <p>Цвет выводится из имени, поэтому одна и та же ветка сохраняет один цвет на разных
 * досках и после перезапусков, а не зависит от порядка создания.
 */
public class TrelloLabelResolver {

    private static final Logger log = LoggerFactory.getLogger(TrelloLabelResolver.class);

    /** Палитра меток Trello. Порядок важен лишь тем, что он должен оставаться неизменным. */
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
     * @param names имена меток в отображаемом виде; пустые и повторы отбрасываются
     * @return id соответствующих меток; недостающие создаются
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
                // Возможно, метку кто-то добавил уже после заполнения кеша.
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
            // Отсутствие метки — косметика; уронить из-за неё карточку было бы хуже.
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

    /** Устойчиво к перезапускам: одно имя — один цвет. */
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
