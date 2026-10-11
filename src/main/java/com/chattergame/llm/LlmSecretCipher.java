package com.chattergame.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Шифрование ключей LLM: AES-GCM, ключ только из секрета окружения (`D-093`). Без шифрования
 * ключ утекает через любой дамп БД, а это чужие деньги и чужая квота.
 *
 * <p>Ключ читается при первой операции, а не в конструкторе: иначе отсутствие секрета
 * роняло бы старт приложения в `dev`, где ключи LLM не нужны. Значение ключа не попадает
 * ни в исключения, ни в логи.
 */
@Component
public class LlmSecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String encodedKey;
    private volatile SecretKey key;

    public LlmSecretCipher(@Value("${chattergame.llm.secret-key:}") String encodedKey) {
        this.encodedKey = encodedKey;
    }

    public Sealed encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] out = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            int tagLength = TAG_BITS / 8;
            return new Sealed(
                    Arrays.copyOf(out, out.length - tagLength),
                    iv,
                    Arrays.copyOfRange(out, out.length - tagLength, out.length));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Не удалось зашифровать ключ LLM", e);
        }
    }

    public String decrypt(byte[] ciphertext, byte[] iv, byte[] tag) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] joined = new byte[ciphertext.length + tag.length];
            System.arraycopy(ciphertext, 0, joined, 0, ciphertext.length);
            System.arraycopy(tag, 0, joined, ciphertext.length, tag.length);
            return new String(cipher.doFinal(joined), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Не удалось расшифровать ключ LLM", e);
        }
    }

    private SecretKey key() {
        SecretKey existing = key;
        if (existing != null) {
            return existing;
        }
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IllegalStateException(
                    "Не задан chattergame.llm.secret-key (секрет окружения): без него ключи LLM "
                            + "хранить нельзя (D-093)");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(encodedKey.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("chattergame.llm.secret-key должен быть в base64");
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "chattergame.llm.secret-key должен быть " + KEY_BYTES + " байтами в base64");
        }
        SecretKey created = new SecretKeySpec(raw, "AES");
        key = created;
        return created;
    }

    public record Sealed(byte[] ciphertext, byte[] iv, byte[] tag) {
    }
}
