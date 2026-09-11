package com.example.inframanager.trello;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Сопоставляет автора пул-реквеста с участником доски Trello.
 *
 * <p>Участника, в отличие от метки, создать нельзя: у человека либо есть учётная запись
 * Trello на этой доске, либо нет. Общего идентификатора у двух систем тоже нет — на
 * доску приглашают по личным почтам, а корпоративный адрес из Bitbucket с ними не
 * связан, и чужие e-mail API Trello не отдаёт. Поэтому основной способ связи здесь —
 * сравнение имён ({@link PersonName}), устойчивое к порядку слов, отчеству и записи
 * латиницей или кириллицей. Держится это на том, что человек оставил в профиле Trello
 * своё настоящее имя.
 *
 * <p>Порядок проверок: явное соответствие из {@code infra-manager.trello.members},
 * затем точное совпадение с username, затем имя. Совпадение по имени принимается,
 * только если подошёл ровно один участник доски: назначить не того человека хуже, чем
 * не назначить никого.
 *
 * <p>Если не подошёл никто, карточка остаётся без исполнителя — сам по себе это не сбой,
 * участников репозитория не обязательно приглашают на доску. Но в лог это попадает
 * (однократно за время жизни кэша), потому что куда чаще означает, что в профиле Trello
 * стоит не настоящее имя.
 */
public class TrelloMemberResolver {

    private static final Logger log = LoggerFactory.getLogger(TrelloMemberResolver.class);

    private final TrelloClient client;
    private final TrelloProperties properties;

    /** Ключи приведены к одному виду: Spring при связывании может изменить их регистр. */
    private final Map<String, String> overrides;

    private final Map<String, CachedMembers> cache = new ConcurrentHashMap<>();

    public TrelloMemberResolver(TrelloClient client, TrelloProperties properties) {
        this.client = client;
        this.properties = properties;
        this.overrides = normaliseKeys(properties.members());
    }

    /**
     * @param candidates идентификаторы для перебора, от наиболее точного (логин, затем
     *                   отображаемое имя)
     * @return id подошедшего участника доски одним элементом; пусто, если не совпал никто
     */
    public List<String> memberIds(String boardId, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        CachedMembers members = members(boardId);
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String id = resolve(members, boardId, candidate.trim());
            if (id != null) {
                return List.of(id);
            }
        }
        // Пустой ростер — это уже отдельная жалоба в fetch(); второй раз не шумим.
        if (!members.roster().isEmpty() && members.firstTime("unmatched:" + candidates)) {
            log.warn("No member of Trello board {} matches {}; ask them to put their real name "
                            + "in their Trello profile, or add the mapping to infra-manager.trello.members",
                    boardId, candidates);
        }
        return List.of();
    }

    public void invalidate(String boardId) {
        cache.remove(boardId);
    }

    private String resolve(CachedMembers members, String boardId, String candidate) {
        String configured = overrides.get(normalise(candidate));
        if (configured != null) {
            String id = members.byIdentifier().get(normalise(configured));
            if (id != null) {
                return id;
            }
            // Опечатка в карте иначе выглядит ровно как «человека нет на доске».
            if (members.firstTime("override:" + configured)) {
                log.warn("infra-manager.trello.members maps '{}' to '{}', but no member of board {} "
                        + "has that id, username or full name", candidate, configured, boardId);
            }
        }

        String byUsername = members.byUsername().get(normalise(candidate));
        if (byUsername != null) {
            return byUsername;
        }

        PersonName name = PersonName.of(candidate);
        List<Member> matches = new ArrayList<>();
        for (Member member : members.roster()) {
            if (member.name().matches(name)) {
                matches.add(member);
            }
        }
        if (matches.size() == 1) {
            Member matched = matches.get(0);
            if (members.firstTime("matched:" + candidate)) {
                // Сопоставление по имени — единственная догадка во всей цепочке, так что
                // его результат должен быть виден, а не только его отсутствие.
                log.info("Matched '{}' to Trello member @{} ({}) by name",
                        candidate, matched.username(), matched.fullName());
            }
            return matched.id();
        }
        if (matches.size() > 1 && members.firstTime("ambiguous:" + candidate)) {
            log.warn("'{}' matches {} members of board {} ({}); assigning nobody -- "
                            + "name the right one in infra-manager.trello.members",
                    candidate, matches.size(), boardId, matches.stream().map(Member::username).toList());
        }
        return null;
    }

    private CachedMembers members(String boardId) {
        CachedMembers cached = cache.get(boardId);
        if (cached == null || cached.isExpired(properties.listCacheTtl())) {
            cached = fetch(boardId);
            cache.put(boardId, cached);
        }
        return cached;
    }

    private CachedMembers fetch(String boardId) {
        List<Member> roster = new ArrayList<>();
        Map<String, String> byUsername = new HashMap<>();
        Map<String, String> byIdentifier = new HashMap<>();
        try {
            List<TrelloClient.TrelloMember> members =
                    client.boardMembers(boardId, properties.key(), properties.token());
            for (TrelloClient.TrelloMember member : members == null ? List.<TrelloClient.TrelloMember>of() : members) {
                if (member == null || member.id() == null) {
                    continue;
                }
                roster.add(new Member(member.id(), member.username(), member.fullName(),
                        PersonName.of(member.fullName())));
                byIdentifier.putIfAbsent(normalise(member.id()), member.id());
                if (member.username() != null && !member.username().isBlank()) {
                    byUsername.putIfAbsent(normalise(member.username()), member.id());
                    byIdentifier.putIfAbsent(normalise(member.username()), member.id());
                }
                if (member.fullName() != null && !member.fullName().isBlank()) {
                    byIdentifier.putIfAbsent(normalise(member.fullName()), member.id());
                }
            }
        } catch (Exception e) {
            // Назначение участника — косметика; уронить из-за неё карточку было бы хуже.
            log.warn("Could not read members of Trello board {}", boardId, e);
        }
        if (roster.isEmpty()) {
            log.warn("Trello board {} has no readable members; cards will have no assignee", boardId);
        }
        return new CachedMembers(List.copyOf(roster), Map.copyOf(byUsername), Map.copyOf(byIdentifier),
                Instant.now(), ConcurrentHashMap.newKeySet());
    }

    private static Map<String, String> normaliseKeys(Map<String, String> configured) {
        if (configured == null || configured.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalised = new HashMap<>();
        configured.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                normalised.put(normalise(key), value.trim());
            }
        });
        return Map.copyOf(normalised);
    }

    private static String normalise(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private record Member(String id, String username, String fullName, PersonName name) {
    }

    /**
     * @param byUsername   только username: точное совпадение, на которое можно положиться
     * @param byIdentifier id, username и полное имя — то, чем разрешается значение из
     *                     конфигурации, где человек уже выбрал участника осознанно
     * @param logged       о чём по этому ростеру уже рассказали; обновляется вместе с ним,
     *                     так что жалоба повторится, но не на каждое событие
     */
    private record CachedMembers(List<Member> roster,
                                 Map<String, String> byUsername,
                                 Map<String, String> byIdentifier,
                                 Instant fetchedAt,
                                 Set<String> logged) {

        boolean isExpired(Duration ttl) {
            return fetchedAt.plus(ttl).isBefore(Instant.now());
        }

        boolean firstTime(String key) {
            return logged.add(key);
        }
    }
}
