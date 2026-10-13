package com.chattergame.restriction;

import com.chattergame.message.SubjectKind;

/**
 * Виды ограничений (`REQUIREMENTS §13`, `D-064`). Ограничение отражает игровую ситуацию —
 * сон, пленение, потеря голоса, абьюз шутками, — а не наказание; поэтому причина
 * обязательна, а снятие штатно (`D-064`).
 *
 * <p>`NO_MOVE` уже объявлено, хотя перемещение по переходам появится вместе с занятостью
 * (`E13`, `D-109`): словарь — часть контракта, и дописывать его задним числом значит менять
 * уже сохранённые значения колонки.
 */
public enum RestrictionKind {

    /** Запрет речи. Цель — персонаж. */
    NO_SPEECH(SubjectKind.CHARACTER),

    /** Запрет действия. Цель — персонаж. */
    NO_ACTION(SubjectKind.CHARACTER),

    /** Запрет перемещения. Цель — персонаж. */
    NO_MOVE(SubjectKind.CHARACTER),

    /**
     * Запрет шутки. Цель — участие, а не персонаж: шутками абьюзят игроки (`D-099`),
     * и это решение заменяет часть `D-064` в части субъекта.
     */
    NO_JOKE(SubjectKind.MEMBERSHIP);

    private final SubjectKind subjectKind;

    RestrictionKind(SubjectKind subjectKind) {
        this.subjectKind = subjectKind;
    }

    public SubjectKind subjectKind() {
        return subjectKind;
    }
}
