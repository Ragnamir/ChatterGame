package com.chattergame.character;

import com.chattergame.util.ApiException;

import java.util.ArrayList;
import java.util.List;

/**
 * Текстовые поля карточки персонажа, которые правит заявка (`REQUIREMENTS §17`: «внешность,
 * открытая история, закрытая история»). Список закрыт намеренно: `portraitAssetId`,
 * `playable` и `npc` — структура карточки, а не текст, и правка их заявкой позволила бы
 * игроку через одобрение мастера переписать признак играбельности. Имя оставлено: без него
 * правка карточки заявкой бесполезна, а риска оно не несёт.
 *
 * <p>Словарь живёт в пакете персонажа, а не в пакете заявок: это свойство карточки, и
 * держать второй перечень полей в применителе значило бы завести дыру, которую никто
 * не заметит (`D-033`). Имена в payload — те же `camelCase`, что у {@code CharacterRequest},
 * а колонка берётся из словаря: имя поля приходит извне, и подстановка его в SQL была бы
 * инъекцией.
 */
public enum CharacterField {

    NAME("name", "name"),
    APPEARANCE("appearance", "appearance"),
    PUBLIC_HISTORY("publicHistory", "public_history"),
    PRIVATE_HISTORY("privateHistory", "private_history");

    private final String wireName;
    private final String column;

    CharacterField(String wireName, String column) {
        this.wireName = wireName;
        this.column = column;
    }

    public String wireName() {
        return wireName;
    }

    public String column() {
        return column;
    }

    /** Текущее значение поля: с него начинается правка и им же откатывается заявка. */
    public String valueIn(Character character) {
        return switch (this) {
            case NAME -> character.name();
            case APPEARANCE -> character.appearance();
            case PUBLIC_HISTORY -> character.publicHistory();
            case PRIVATE_HISTORY -> character.privateHistory();
        };
    }

    /** Предел значения: объявленный у имени; у остальных полей колонка — `text`. */
    public int maxLength() {
        return this == NAME ? Character.NAME_MAX : Integer.MAX_VALUE;
    }

    /** Разбор имени поля из payload: неизвестное поле — ошибка клиента, а не молчаливый пропуск. */
    public static CharacterField of(String wireName) {
        if (wireName != null) {
            for (CharacterField field : values()) {
                if (field.wireName.equals(wireName.strip())) {
                    return field;
                }
            }
        }
        throw ApiException.badRequest(
                "unknown_character_field",
                "Правке заявкой доступны: " + String.join(", ", wireNames()));
    }

    public static List<String> wireNames() {
        List<String> names = new ArrayList<>();
        for (CharacterField field : values()) {
            names.add(field.wireName);
        }
        return names;
    }
}
