package com.chattergame.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Токены сессии и сброса пароля. Наружу уходит 256 бит случайности, в БД — их SHA-256:
 * дамп таблицы не даёт возможности ни представиться чужой сессией, ни открыть ссылку
 * из письма. Хеш без соли здесь достаточен, потому что входное значение уже случайно
 * и перебор по словарю невозможен.
 */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private Tokens() {
    }

    public static String issue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 64 шестнадцатеричных символа — ровно ширина колонки `token_hash`. */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
