package com.example.inframanager.trello;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Сопоставляет автора пул-реквеста с участником доски Trello.
 *
 * <p>В отличие от меток, участников создать нельзя: у человека либо есть учётная запись
 * Trello на этой доске, либо нет. Если никто не совпал, карточка просто остаётся без
 * исполнителя — это нормальный исход, а не сбой, ведь участников репозитория не
 * обязательно приглашают на доску.
 *
 * <p>Сопоставление сначала пробует логин Bitbucket против username в Trello, затем
 * отображаемое имя против полного имени участника — и то и другое без учёта регистра.
 */
public class TrelloMemberResolver {

    private static final Logger log = LoggerFactory.getLogger(TrelloMemberResolver.class);

    private final TrelloClient client;
    private final TrelloProperties properties;
    private final Map<String, CachedMembers> cache = new ConcurrentHashMap<>();

    public TrelloMemberResolver(TrelloClient client, TrelloProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * @param candidates идентификаторы для перебора, от наиболее точного (логин, затем
     *                   отображаемое имя)
     * @return id подошедших участников доски; пусто, если не совпал никто
     */
    public List<String> memberIds(String boardId, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, String> byIdentifier = members(boardId);
        List<String> ids = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            // Сначала соответствие из конфигурации: один и тот же человек обычно записан
            // в Bitbucket и Trello по-разному, так что это единственная надёжная связь.
            String mapped = properties.members().get(candidate.trim());
            String id = mapped != null ? byIdentifier.get(normalise(mapped)) : null;
            if (id == null) {
                id = byIdentifier.get(normalise(candidate));
            }
            if (id != null && !ids.contains(id)) {
                ids.add(id);
                break;
            }
        }
        if (ids.isEmpty()) {
            log.debug("No Trello member on board {} matches {}", boardId, candidates);
        }
        return ids;
    }

    public void invalidate(String boardId) {
        cache.remove(boardId);
    }

    private Map<String, String> members(String boardId) {
        CachedMembers cached = cache.get(boardId);
        if (cached == null || cached.isExpired(properties.listCacheTtl())) {
            cached = fetch(boardId);
            cache.put(boardId, cached);
        }
        return cached.idsByIdentifier();
    }

    private CachedMembers fetch(String boardId) {
        Map<String, String> byIdentifier = new java.util.HashMap<>();
        try {
            List<TrelloClient.TrelloMember> members =
                    client.boardMembers(boardId, properties.key(), properties.token());
            for (TrelloClient.TrelloMember member : members == null ? List.<TrelloClient.TrelloMember>of() : members) {
                if (member.username() != null) {
                    byIdentifier.put(normalise(member.username()), member.id());
                }
                if (member.fullName() != null && !member.fullName().isBlank()) {
                    byIdentifier.putIfAbsent(normalise(member.fullName()), member.id());
                }
            }
        } catch (Exception e) {
            // Назначение участника — косметика; уронить из-за неё карточку было бы хуже.
            log.warn("Could not read members of Trello board {}", boardId, e);
        }
        return new CachedMembers(Map.copyOf(byIdentifier), Instant.now());
    }

    private static String normalise(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private record CachedMembers(Map<String, String> idsByIdentifier, Instant fetchedAt) {

        boolean isExpired(Duration ttl) {
            return fetchedAt.plus(ttl).isBefore(Instant.now());
        }
    }
}
