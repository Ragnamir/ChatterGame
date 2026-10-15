package com.chattergame.note;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Совпадение ключей без базы (`PLAN` E12, задачи 3 и 5): лемматизация Snowball, обязательная
 * граница слова и режим точного совпадения (`D-051`), нормализация (`D-080`).
 *
 * <p>Проверки идут на чистой функции намеренно: здесь ловится именно морфология, а
 * интеграционный тест с базой показал бы только «подсветка есть», скрыв, что она есть
 * и там, где не должна.
 */
class E12KeywordMatcherTest {

    @Test
    void lemmaMatchesObliqueCases() {
        assertThat(KeywordMatcher.matches("козлоногий", false, "он был с козлоногим уродом"))
                .as("падежная форма находится леммой, а не подстрокой (D-051)")
                .isTrue();
        assertThat(KeywordMatcher.matches("козлоногий", false, "козлоногие твари ушли"))
                .as("множественное число — та же основа")
                .isTrue();
        assertThat(KeywordMatcher.matches("выходить", false, "он выходит из тени"))
                .as("глагольная форма приводится к основе Snowball")
                .isTrue();
    }

    @Test
    void wordBoundaryIsMandatory() {
        assertThat(KeywordMatcher.matches("козлоногий", false, "в чужой полукозлоногий мир"))
                .as("ключ не подсвечивается внутри другого слова — это критерий приёмки E12")
                .isFalse();
        assertThat(KeywordMatcher.matches("козлоногий", false, "козлоногий"))
                .as("одиночное слово совпадает")
                .isTrue();
    }

    @Test
    void exactModeDoesNotCatchIndirectCases() {
        assertThat(KeywordMatcher.matches("Иван", true, "у Ивана в руках фонарь"))
                .as("точный режим для имени не ловит косвенный падеж (D-051)")
                .isFalse();
        assertThat(KeywordMatcher.matches("Иван", true, "Иван вошёл первым"))
                .as("точный режим ловит ровно ту форму, что названа")
                .isTrue();
        assertThat(KeywordMatcher.matches("Иван", false, "у Ивана в руках фонарь"))
                .as("без точного режима форма косвенного падежа находится — в этом и риск (D-051)")
                .isTrue();
    }

    @Test
    void multiWordKeywordNeedsConsecutiveWords() {
        assertThat(KeywordMatcher.matches("козлоногий урод", false, "с козлоногим уродом на плечах"))
                .as("ключ из двух слов находится подряд, в тех же формах")
                .isTrue();
        assertThat(KeywordMatcher.matches("козлоногий урод", false, "урод, козлоногий"))
                .as("обратный порядок — не то же словосочетание")
                .isFalse();
    }

    @Test
    void normalizationSurvivesNfdAndCase() {
        assertThat(KeywordMatcher.normalize("Й"))
                .as("NFC плюс нижний регистр: NFD-«й» из двух кодпоинтов сводится к одной форме (D-080)")
                .isEqualTo("й");
        assertThat(KeywordMatcher.matches("Ёлка", false, "ёлки в снегу"))
                .as("регистр не мешает совпадению")
                .isTrue();
    }

    @Test
    void emptyKeywordMatchesNothing() {
        assertThat(KeywordMatcher.matches("   ", false, "любое тело"))
                .as("пустой ключ не подсвечивает: иначе заметка без слов светила бы всю ленту")
                .isFalse();
        assertThat(KeywordMatcher.matches("слово", false, "   "))
                .as("пустое тело не совпадает ни с чем")
                .isFalse();
    }

    @Test
    void storedFormIsStemForLemmaAndWholeFormForExact() {
        assertThat(KeywordMatcher.storedForm("Козлоногий", false))
                .as("в режиме леммы хранится основа — её и сравнивают при записи")
                .isEqualTo(KeywordMatcher.stem("козлоногий"));
        assertThat(KeywordMatcher.storedForm("Иван", true))
                .as("в точном режиме хранится нормализованная форма слова целиком")
                .isEqualTo("иван");
    }
}
