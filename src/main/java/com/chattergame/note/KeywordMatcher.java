package com.chattergame.note;

import org.tartarus.snowball.ext.RussianStemmer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Совпадение ключевого слова заметки с телом сообщения (`REQUIREMENTS §15.1`, `D-051`).
 *
 * <p>Три решения этого класса — не украшение, а содержание требования:
 *
 * <ol>
 *   <li>Лемма, а не подстрока. Русская основа меняется по падежам, и «козлоногий» не
 *       находится в «с козлоногим» точным сравнением (`D-051`). Стеммер — Snowball для
 *       русского (`lucene-analysis-common`), не самописный: самодельная реализация
 *       расходится с эталоном на падежных формах и даёт пропущенные подсветки.</li>
 *   <li>Обязательная граница слова. Сравниваются целые слова, а не вхождения подстроки:
 *       иначе «козлоногий» подсветился бы внутри «полукозлоногий», а требование прямо
 *       называет этот случай отрицательным.</li>
 *   <li>Флаг точного совпадения для имён (`D-051`). Имя «Иван» в тексте «у Ивана» леммой
 *       находится, и для имён это ложное срабатывание: тёзка — не тот же человек. Поэтому
 *       у ключа есть режим, в котором сравнивается нормализованная форма слова целиком.</li>
 * </ol>
 *
 * <p>Нормализация — NFC плюс нижний регистр, посчитанный сервером (`D-080`): macOS отдаёт
 * ключи в NFD, и без нормализации «й» из двух кодпоинтов не нашла бы себя же.
 *
 * <p>Класс вызывается только в пути записи и в досчёте (`D-050`, `D-087`). В read-пути
 * совпадение не вычисляется никогда: это проверяется правилом `SourceRules` на гейте.
 */
public final class KeywordMatcher {

    /** Ограничение числа слов в ключе: фраза длиннее — это не ключ, а абзац. */
    public static final int MAX_KEYWORD_LENGTH = 128;

    private KeywordMatcher() {
    }

    /** Нормализованная форма строки: края обрезаны, NFC, нижний регистр (`D-080`). */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return Normalizer.normalize(raw.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    /**
     * Основа слова по Snowball. Нужна для сравнения в режиме леммы и хранится в
     * `note_keyword.normalized`, чтобы при записи сообщения не считать стеммер повторно
     * на каждый ключ — он уже посчитан при создании заметки.
     */
    public static String stem(String normalized) {
        RussianStemmer stemmer = new RussianStemmer();
        stemmer.setCurrent(normalized);
        stemmer.stem();
        return stemmer.getCurrent();
    }

    /**
     * Что хранить в `note_keyword.normalized`: лемму либо точную форму — по режиму ключа.
     *
     * <p>Стеммер применяется к каждому слову отдельно, а не к строке целиком: Snowball
     * отрезает окончание у последнего слова фразы и превратил бы «козлоногий урод» в одну
     * основу, из-за чего ключ из нескольких слов не находился бы никогда. Разбиение по
     * пробелам здесь честное: ключ — это слова, а не текст с пунктуацией.
     */
    public static String storedForm(String raw, boolean exactMatch) {
        String normalized = normalize(raw);
        if (exactMatch) {
            return normalized;
        }
        List<String> words = new ArrayList<>();
        for (String word : normalized.split("\\s+")) {
            if (!word.isEmpty()) {
                words.add(stem(word));
            }
        }
        return String.join(" ", words);
    }

    /**
     * Совпадает ли тело сообщения с ключом. В режиме леммы сравниваются основы слов, в
     * режиме точного совпадения — нормализованные слова целиком. Ключ из нескольких слов
     * обязан встретиться подряд, иначе «козлоногий урод» нашёлся бы в «урод, козлоногий».
     */
    public static boolean matches(String keyword, boolean exactMatch, String body) {
        String normalizedStored = storedForm(keyword, exactMatch);
        if (normalizedStored.isEmpty()) {
            return false;
        }
        List<String> wanted = List.of(normalizedStored.split("\\s+"));
        List<String> words = words(body);
        for (int start = 0; start + wanted.size() <= words.size(); start++) {
            boolean same = true;
            for (int offset = 0; offset < wanted.size(); offset++) {
                String word = words.get(start + offset);
                String form = exactMatch ? word : stem(word);
                if (!wanted.get(offset).equals(form)) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return true;
            }
        }
        return false;
    }

    /**
     * Слова тела в нормализованном виде. Разделителем считается всё, что не буква и не
     * цифра: так «с козлоногим уродом» даёт три слова, а «полукозлоногий» — одно, и
     * граница слова выполняется на уровне разбиения, а не проверкой соседних символов.
     */
    public static List<String> words(String body) {
        List<String> words = new ArrayList<>();
        if (body == null) {
            return words;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                current.append(c);
            } else if (!current.isEmpty()) {
                words.add(normalize(current.toString()));
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            words.add(normalize(current.toString()));
        }
        return words;
    }
}
