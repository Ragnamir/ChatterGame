package com.chattergame.item;

/**
 * Тип предмета (`REQUIREMENTS §10`, `D-114`). Закрытый перечень, как словарь событий
 * (`D-102`): от типа зависят подсказка мастеру и признак исчисляемости, а произвольная
 * строка не даёт ни того, ни другого.
 *
 * <p>Исчисляемость выводится из типа, а не задаётся флагом на предмете: это одно место
 * правды. `MISC` исчисляемый, потому что в него попадает всё подряд, включая то, что мастер
 * захочет сложить в стопку.
 */
public enum ItemType {

    WEAPON(false),
    ARMOR(false),
    CLOTHING(false),
    TOOL(false),
    DOCUMENT(false),
    KEY(false),
    VALUABLE(false),
    CONSUMABLE(true),
    MONEY(true),
    QUEST(false),
    MISC(true);

    private final boolean countable;

    ItemType(boolean countable) {
        this.countable = countable;
    }

    /** У неисчисляемого типа количество всегда `1`, и сервис это проверяет (`D-114`). */
    public boolean countable() {
        return countable;
    }
}
