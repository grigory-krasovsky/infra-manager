package com.example.inframanager.trello;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Приводит чек-лист карточки к списку задач ревью.
 *
 * <p>Работает только со своим чек-листом — тем, что назван {@link #NAME}. Всё
 * остальное, что человек завёл на карточке руками, не трогается: доска принадлежит
 * людям, а мы на ней всего лишь ведём одну колонку фактов.
 *
 * <p>Пункты сверяются по тексту. Ревьюер, переписавший задачу, получит новый пункт
 * вместо старого — состояние при этом не теряется, потому что новый добавляется сразу
 * отмеченным, если задача закрыта.
 */
public class TrelloChecklistSync {

    private static final Logger log = LoggerFactory.getLogger(TrelloChecklistSync.class);

    static final String NAME = "Задачи ревью";

    private final TrelloClient client;
    private final TrelloProperties properties;

    public TrelloChecklistSync(TrelloClient client, TrelloProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * @param desired null означает «не трогать»: задачи не спрашивали или спросить не
     *                удалось. Пустой список — «задач нет», и чек-лист снимается.
     */
    public void sync(String cardId, List<TrelloCardCommand.ChecklistItem> desired) {
        if (desired == null) {
            return;
        }
        try {
            apply(cardId, desired);
        } catch (Exception e) {
            // Чек-лист — это подсказка, а не состояние карточки. Уронить из-за него
            // всю синхронизацию значило бы потерять и колонку, и заголовок.
            log.warn("Could not sync the review checklist of card {}", cardId, e);
        }
    }

    private void apply(String cardId, List<TrelloCardCommand.ChecklistItem> desired) {
        TrelloClient.TrelloChecklist ours = ours(cardId);

        if (desired.isEmpty()) {
            if (ours != null) {
                client.deleteChecklist(ours.id(), properties.key(), properties.token());
                log.info("Removed the review checklist from card {}: no tasks left", cardId);
            }
            return;
        }

        if (ours == null) {
            String checklistId = client.createChecklist(
                    properties.key(), properties.token(), cardId, NAME).id();
            for (TrelloCardCommand.ChecklistItem item : desired) {
                add(checklistId, item);
            }
            log.info("Added a review checklist with {} item(s) to card {}", desired.size(), cardId);
            return;
        }

        // По имени, а не по порядку: Trello хранит свой порядок, а Bitbucket — свой.
        Map<String, TrelloClient.TrelloCheckItem> existing = new LinkedHashMap<>();
        for (TrelloClient.TrelloCheckItem item : ours.items()) {
            if (item != null && item.name() != null) {
                existing.putIfAbsent(key(item.name()), item);
            }
        }

        int added = 0;
        int changed = 0;
        for (TrelloCardCommand.ChecklistItem item : desired) {
            TrelloClient.TrelloCheckItem found = existing.remove(key(item.name()));
            if (found == null) {
                add(ours.id(), item);
                added++;
            } else if (found.isComplete() != item.done()) {
                client.updateCheckItem(cardId, found.id(), properties.key(), properties.token(),
                        item.done() ? TrelloClient.TrelloCheckItem.COMPLETE
                                : TrelloClient.TrelloCheckItem.INCOMPLETE);
                changed++;
            }
        }

        List<String> removed = new ArrayList<>();
        for (TrelloClient.TrelloCheckItem leftover : existing.values()) {
            client.deleteCheckItem(ours.id(), leftover.id(), properties.key(), properties.token());
            removed.add(leftover.name());
        }

        if (added > 0 || changed > 0 || !removed.isEmpty()) {
            log.info("Review checklist of card {}: +{} item(s), {} re-ticked, -{} removed",
                    cardId, added, changed, removed.size());
        }
    }

    private TrelloClient.TrelloChecklist ours(String cardId) {
        List<TrelloClient.TrelloChecklist> checklists = client.cardChecklists(
                cardId, properties.key(), properties.token(), "all", "name,state", "name");
        if (checklists == null) {
            return null;
        }
        return checklists.stream()
                .filter(checklist -> checklist != null && NAME.equalsIgnoreCase(checklist.name()))
                .findFirst()
                .orElse(null);
    }

    private void add(String checklistId, TrelloCardCommand.ChecklistItem item) {
        client.createCheckItem(checklistId, properties.key(), properties.token(),
                item.name(), item.done(), "bottom");
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
