package com.chattergame.user;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Хеширование паролей, Argon2id. Используется кодировщик из `spring-security-crypto`,
 * а не собственная проверка: сравнение хешей в постоянном времени и формат кодирования —
 * ровно тот код, где самодельная реализация даёт дыры.
 *
 * <p>Параметры зафиксированы, потому что они входят в формат хеша: их смена — это решение
 * с планом перехеширования, а не настройка на глаз.
 */
@Component
public class PasswordHasher {

    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_KIB = 1 << 14;
    private static final int ITERATIONS = 2;

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 128;

    private final PasswordEncoder encoder =
            new Argon2PasswordEncoder(SALT_LENGTH, HASH_LENGTH, PARALLELISM, MEMORY_KIB, ITERATIONS);

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /** Хеш отсутствует у анонимизированного аккаунта, поэтому `null` — не исключение. */
    public boolean matches(String rawPassword, String encodedHash) {
        return encodedHash != null && !encodedHash.isBlank() && encoder.matches(rawPassword, encodedHash);
    }

    public static boolean lengthAllowed(String rawPassword) {
        return rawPassword.length() >= MIN_LENGTH && rawPassword.length() <= MAX_LENGTH;
    }
}
