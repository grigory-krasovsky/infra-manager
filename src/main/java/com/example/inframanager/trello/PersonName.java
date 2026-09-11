package com.example.inframanager.trello;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Имя человека, приведённое к виду, в котором его можно сравнивать между системами.
 *
 * <p>Общего идентификатора у Bitbucket и Trello нет: учётные записи Trello заведены на
 * личные почты, а чужие e-mail API Trello не отдаёт. Остаётся имя — но записано оно
 * по-разному: «Красовский Григорий Игоревич» против «Grigory Krasovsky», кириллицей
 * против латиницы, в другом порядке, с отчеством и без. Побуквенное сравнение такие
 * пары не связывает.
 *
 * <p>Поэтому имя разбирается на слова, каждое переводится в латиницу и сворачивается до
 * формы, одинаковой для расхожих вариантов записи одного звука ({@code kh}/{@code h},
 * {@code ts}/{@code c}, {@code y}/{@code i}, сдвоенные буквы), а сравниваются уже
 * множества слов — порядок не важен, лишние слова игнорируются.
 *
 * <p>Совпадением считаются минимум два слова, обычно имя и фамилия: одной фамилии мало,
 * чтобы назначить человека на карточку. Когда в одном из имён слово всего одно, хватает
 * и его, но такое совпадение принимается только как единственное на доске — это решает
 * {@link TrelloMemberResolver}.
 */
final class PersonName {

    /** Сколько первых букв должно совпасть, чтобы «Alexander» и «Alex» считались одним словом. */
    private static final int PREFIX_MATCH = 4;

    private static final int REQUIRED_WORDS = 2;

    private static final String CYRILLIC = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя";

    private static final String[] LATIN = {
            "a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "i", "k", "l", "m", "n", "o", "p",
            "r", "s", "t", "u", "f", "h", "c", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya"
    };

    private final List<String> words;

    private PersonName(List<String> words) {
        this.words = words;
    }

    /**
     * @param raw отображаемое имя или логин; логин вида {@code g.krasovsky} тоже разбирается
     *            на слова и потому сравнивается с именем наравне
     */
    static PersonName of(String raw) {
        if (raw == null || raw.isBlank()) {
            return new PersonName(List.of());
        }
        List<String> words = new ArrayList<>();
        for (String word : raw.split("[^\\p{L}\\p{N}]+")) {
            String folded = fold(word);
            if (!folded.isEmpty()) {
                words.add(folded);
            }
        }
        return new PersonName(List.copyOf(words));
    }

    boolean isEmpty() {
        return words.isEmpty();
    }

    boolean matches(PersonName other) {
        if (isEmpty() || other.isEmpty()) {
            return false;
        }
        // Требуем два слова, но не больше, чем есть у более короткого имени: «Вася» в
        // профиле Trello — одно слово, и требовать от него фамилию бессмысленно.
        int required = Math.min(REQUIRED_WORDS, Math.min(words.size(), other.words.size()));
        boolean[] taken = new boolean[other.words.size()];
        int matched = 0;
        for (String word : words) {
            for (int i = 0; i < other.words.size(); i++) {
                if (!taken[i] && sameWord(word, other.words.get(i))) {
                    // Каждое слово расходуется однократно, иначе «Иван Иванов» совпал бы
                    // сам с собой дважды по одному слову.
                    taken[i] = true;
                    matched++;
                    break;
                }
            }
        }
        return matched >= required;
    }

    @Override
    public String toString() {
        return String.join(" ", words);
    }

    private static boolean sameWord(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        // Инициал: «Г. Красовский» — тот же человек, что «Григорий Красовский».
        if (a.length() == 1 || b.length() == 1) {
            return a.charAt(0) == b.charAt(0);
        }
        return commonPrefix(a, b) >= PREFIX_MATCH;
    }

    private static int commonPrefix(String a, String b) {
        int limit = Math.min(a.length(), b.length());
        int i = 0;
        while (i < limit && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    private static String fold(String word) {
        String latin = transliterate(word);
        // Разные написания одного звука сводятся к одной букве, потому что человек сам
        // пишет своё имя латиницей то так, то иначе: Mikhail/Mihail, Kuznetsov/Kuznecov,
        // Grigory/Grigoriy/Grigorii.
        latin = latin.replace("shch", "s")
                .replace("sch", "s")
                .replace("sh", "s")
                .replace("ch", "c")
                .replace("zh", "z")
                .replace("kh", "h")
                .replace("ts", "c")
                .replace("yu", "u")
                .replace("ya", "a")
                .replace("yo", "o")
                .replace('y', 'i')
                .replace('j', 'i')
                .replace('w', 'v');
        return collapseRepeats(latin);
    }

    private static String transliterate(String word) {
        StringBuilder out = new StringBuilder(word.length());
        for (char c : word.toLowerCase(Locale.ROOT).toCharArray()) {
            int index = CYRILLIC.indexOf(c);
            if (index >= 0) {
                out.append(LATIN[index]);
            } else if (Character.isLetterOrDigit(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** «Krasovskii» и «Krasovski», «Anna» и «Ana» — одно и то же слово. */
    private static String collapseRepeats(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (out.isEmpty() || out.charAt(out.length() - 1) != c) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
