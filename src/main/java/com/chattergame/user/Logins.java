package com.chattergame.user;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Нормализация логина (`D-080`). Уникальность и сравнение идут по NFC-нормализованному
 * нижнему регистру, посчитанному сервером: кириллица допустима, а macOS отдаёт имена
 * в NFD, поэтому `citext` с локальной коллацией дал бы два аккаунта на один логин.
 *
 * <p>Отдельно хранится отображаемая форма: пользователь видит свой логин в том регистре,
 * в каком его набрал.
 */
public final class Logins {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 64;

    private Logins() {
    }

    /** Отображаемая форма: обрезка краёв и NFC, регистр сохраняется. */
    public static String display(String raw) {
        return Normalizer.normalize(raw.strip(), Normalizer.Form.NFC);
    }

    /** Ключ уникальности: NFC плюс нижний регистр. */
    public static String normalize(String raw) {
        return display(raw).toLowerCase(Locale.ROOT);
    }

    public static boolean lengthAllowed(String displayLogin) {
        return displayLogin.length() >= MIN_LENGTH && displayLogin.length() <= MAX_LENGTH;
    }

    /** Управляющие символы в логине недопустимы: они ломают вывод и сравнение. */
    public static boolean hasControlCharacters(String displayLogin) {
        return displayLogin.chars().anyMatch(Character::isISOControl);
    }
}
