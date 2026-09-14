package com.example.inframanager.trello;

import java.util.List;
import java.util.Locale;

/**
 * Палитра Trello — других цветов у неё нет. Одна и та же для меток и для обложек
 * карточек, поэтому и живёт отдельно от тех и от других.
 *
 * <p>Незнакомый цвет Trello принимает молча и не красит ничего, так что сверяться с этим
 * списком нужно при старте: иначе об опечатке узнаёшь по карточке, которая осталась
 * прежней, и гадаешь, дошёл ли до Trello запрос вообще.
 */
public final class TrelloColors {

    public static final List<String> ALL = List.of(
            "green", "yellow", "orange", "red", "purple", "blue", "sky", "lime", "pink", "black");

    private TrelloColors() {
    }

    /**
     * @param property имя настройки для сообщения об ошибке — без него непонятно, какую
     *                 строку конфигурации править
     * @return цвет в том виде, в каком его ждёт Trello, либо null, если значения нет
     * @throws IllegalArgumentException если такого цвета у Trello не существует
     */
    public static String requireKnown(String color, String property) {
        if (color == null || color.isBlank()) {
            return null;
        }
        String normalised = color.trim().toLowerCase(Locale.ROOT);
        if (!ALL.contains(normalised)) {
            throw new IllegalArgumentException(
                    "%s: '%s' is not a Trello colour; allowed: %s".formatted(property, color, ALL));
        }
        return normalised;
    }
}
