package com.example.inframanager.trello;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Превращает имена меток в id, которых требует Trello, создавая те, которых ещё нет.
 *
 * <p>Создаём, а не требуем заранее, потому что значения обнаруживаются, а не
 * настраиваются: новая целевая ветка должна появиться на доске без того, чтобы кто-то
 * сперва правил конфигурацию.
 *
 * <p>Цвет метки, названной в {@code infra-manager.trello.label-colors}, задаёт
 * конфигурация — и не только при создании: если на доске цвет другой, он возвращается к
 * настроенному. Иначе метка, заведённая до настройки, осталась бы прежнего цвета
 * навсегда. Остальным даётся первый ещё не занятый на доске цвет: метки существуют ради
 * взгляда, а две одинаковые плашки взглядом не различить.
 */
public class TrelloLabelResolver {

    private static final Logger log = LoggerFactory.getLogger(TrelloLabelResolver.class);

    /** Палитра меток Trello — других цветов у неё нет. */
    static final List<String> COLORS = List.of(
            "green", "yellow", "orange", "red", "purple", "blue", "sky", "lime", "pink", "black");

    private static final int LABEL_FETCH_LIMIT = 1000;

    private final TrelloClient client;
    private final TrelloProperties properties;
    private final Map<String, String> configuredColors;
    private final Map<String, CachedLabels> cache = new ConcurrentHashMap<>();

    public TrelloLabelResolver(TrelloClient client, TrelloProperties properties) {
        this.client = client;
        this.properties = properties;
        this.configuredColors = validated(properties.labelColors());
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

        Map<String, String> byName = labels(boardId, false).idsByName();
        List<String> ids = new java.util.ArrayList<>();
        for (String name : wanted) {
            String id = byName.get(normalise(name));
            if (id == null) {
                // Возможно, метку кто-то добавил уже после заполнения кеша.
                byName = labels(boardId, true).idsByName();
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
                    properties.key(), properties.token(), boardId, name, colorFor(boardId, name));
            log.info("Created Trello label '{}' ({}) on board {}", name, created.color(), boardId);
            // Чтобы следующая метка того же обновления не получила тот же цвет.
            invalidate(boardId);
            return created.id();
        } catch (Exception e) {
            // Отсутствие метки — косметика; уронить из-за неё карточку было бы хуже.
            log.warn("Could not create Trello label '{}' on board {}", name, boardId, e);
            return null;
        }
    }

    /**
     * Цвет из конфигурации, иначе первый ещё не занятый на доске. Когда занято всё,
     * цвет выводится из имени: повтор неизбежен, но хотя бы предсказуем.
     */
    private String colorFor(String boardId, String name) {
        String configured = configuredColors.get(normalise(name));
        if (configured != null) {
            return configured;
        }
        Set<String> used = labels(boardId, false).usedColors();
        return COLORS.stream()
                .filter(color -> !used.contains(color))
                .findFirst()
                .orElseGet(() -> hashedColor(name));
    }

    private CachedLabels labels(String boardId, boolean forceRefresh) {
        CachedLabels cached = cache.get(boardId);
        if (forceRefresh || cached == null || cached.isExpired(properties.listCacheTtl())) {
            cached = fetch(boardId);
            cache.put(boardId, cached);
        }
        return cached;
    }

    private CachedLabels fetch(String boardId) {
        List<TrelloClient.TrelloLabel> labels =
                client.boardLabels(boardId, properties.key(), properties.token(), LABEL_FETCH_LIMIT);
        Map<String, String> byName = new LinkedHashMap<>();
        Set<String> usedColors = new LinkedHashSet<>();
        for (TrelloClient.TrelloLabel label : labels == null ? List.<TrelloClient.TrelloLabel>of() : labels) {
            if (label == null || label.name() == null || label.name().isBlank()) {
                continue;
            }
            String color = restoreColor(boardId, label);
            byName.putIfAbsent(normalise(label.name()), label.id());
            if (color != null) {
                usedColors.add(color);
            }
        }
        return new CachedLabels(Map.copyOf(byName), Set.copyOf(usedColors), Instant.now());
    }

    /**
     * @return цвет метки после сверки с конфигурацией: настроенный, если его удалось
     *         вернуть, иначе тот, что на доске
     */
    private String restoreColor(String boardId, TrelloClient.TrelloLabel label) {
        String wanted = configuredColors.get(normalise(label.name()));
        if (wanted == null || wanted.equals(label.color())) {
            return label.color();
        }
        try {
            client.updateLabel(label.id(), properties.key(), properties.token(), wanted);
            log.info("Recoloured Trello label '{}' on board {}: {} -> {}",
                    label.name(), boardId, label.color(), wanted);
            return wanted;
        } catch (Exception e) {
            // Цвет — косметика: не удалось перекрасить, значит метка останется прежней.
            log.warn("Could not recolour Trello label '{}' on board {}", label.name(), boardId, e);
            return label.color();
        }
    }

    private static Map<String, String> validated(List<TrelloProperties.LabelColor> configured) {
        if (configured == null || configured.isEmpty()) {
            return Map.of();
        }
        Map<String, String> colors = new LinkedHashMap<>();
        for (TrelloProperties.LabelColor entry : configured) {
            if (entry == null || entry.label() == null || entry.label().isBlank()
                    || entry.color() == null || entry.color().isBlank()) {
                continue;
            }
            String color = entry.color().trim().toLowerCase(Locale.ROOT);
            if (!COLORS.contains(color)) {
                // Trello молча отвергает неизвестный цвет; лучше не собраться при старте,
                // чем выяснять это по метке, которую так и не создали.
                throw new IllegalArgumentException(
                        "infra-manager.trello.label-colors: '%s' is not a Trello label colour; allowed: %s"
                                .formatted(entry.color(), COLORS));
            }
            colors.put(normalise(entry.label()), color);
        }
        return Map.copyOf(colors);
    }

    /** Устойчиво к перезапускам: одно имя — один цвет. Запасной вариант, когда палитра занята. */
    static String hashedColor(String name) {
        int hash = normalise(name).hashCode();
        return COLORS.get(Math.floorMod(hash, COLORS.size()));
    }

    private static String normalise(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private record CachedLabels(Map<String, String> idsByName, Set<String> usedColors, Instant fetchedAt) {

        boolean isExpired(Duration ttl) {
            return fetchedAt.plus(ttl).isBefore(Instant.now());
        }
    }
}
