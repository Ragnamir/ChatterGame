package com.chattergame.user;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Почта: хранится в нижнем регистре, потому что адрес сравнивается без учёта регистра,
 * а частичный уникальный индекс в `app_user` — точный. Без нормализации на входе
 * `Vasya@Example.org` и `vasya@example.org` стали бы двумя аккаунтами.
 *
 * <p>Емейл не является логином (`PLAN` E02): вход идёт по логину, адрес нужен только
 * для подтверждения и сброса пароля.
 */
public final class Emails {

    public static final int MAX_LENGTH = 320;

    private static final Pattern SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private Emails() {
    }

    public static String normalize(String raw) {
        return raw.strip().toLowerCase(Locale.ROOT);
    }

    public static boolean looksValid(String normalized) {
        return normalized.length() <= MAX_LENGTH && SHAPE.matcher(normalized).matches();
    }
}
