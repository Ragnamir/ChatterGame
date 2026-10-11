package com.chattergame.llm;

import com.chattergame.util.ApiException;
import com.chattergame.util.Uuids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ключи LLM пользователя: несколько на аккаунт, один активный (`D-093`). Секрет шифруется
 * при записи, активация снимает флаг с прежнего ключа — иначе частичный уникальный индекс
 * `ux_llm_credential_active` отвергнет вторую активную строку.
 *
 * <p>Открытое значение ключа не читается ни одним методом: расшифровка нужна только
 * будущему вызову провайдера (`E14`), и вводить её раньше — значит держать путь утечки
 * раньше, чем он понадобился.
 */
@Service
public class LlmCredentialService {

    private static final int LABEL_MAX = 64;
    private static final int BASE_URL_MAX = 255;
    private static final int MODEL_MAX = 128;

    private static final RowMapper<LlmCredential> MAPPER = LlmCredentialService::map;

    private final JdbcTemplate jdbc;
    private final LlmSecretCipher cipher;

    public LlmCredentialService(JdbcTemplate jdbc, LlmSecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public List<LlmCredentialView> list(UUID userId) {
        return jdbc.query(
                        "SELECT * FROM llm_credential WHERE user_id = ? ORDER BY created_at",
                        MAPPER,
                        userId)
                .stream()
                .map(LlmCredential::view)
                .toList();
    }

    @Transactional
    public LlmCredentialView create(UUID userId, String label, String baseUrl, String model, String secret) {
        validate(label, baseUrl, model, secret);
        LlmSecretCipher.Sealed sealed = cipher.encrypt(secret);
        UUID id = Uuids.v7();
        jdbc.update(
                "INSERT INTO llm_credential (id, user_id, label, base_url, model, "
                        + "secret_ciphertext, secret_iv, secret_tag, active) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, false)",
                id,
                userId,
                normalizedLabel(label),
                baseUrl.strip(),
                model.strip(),
                sealed.ciphertext(),
                sealed.iv(),
                sealed.tag());
        return find(userId, id).view();
    }

    @Transactional
    public LlmCredentialView setActive(UUID userId, UUID credentialId) {
        find(userId, credentialId);
        jdbc.update("UPDATE llm_credential SET active = false WHERE user_id = ?", userId);
        jdbc.update(
                "UPDATE llm_credential SET active = true, updated_at = now() WHERE id = ?", credentialId);
        return find(userId, credentialId).view();
    }

    public void delete(UUID userId, UUID credentialId) {
        int removed = jdbc.update(
                "DELETE FROM llm_credential WHERE id = ? AND user_id = ?", credentialId, userId);
        if (removed == 0) {
            throw notFound();
        }
    }

    /** Удаление аккаунта: ключи стираются, а не анонимизируются, — они чужие (`D-095`). */
    public void deleteAllForUser(UUID userId) {
        jdbc.update("DELETE FROM llm_credential WHERE user_id = ?", userId);
    }

    LlmCredential find(UUID userId, UUID credentialId) {
        return jdbc.query(
                        "SELECT * FROM llm_credential WHERE id = ? AND user_id = ?",
                        MAPPER,
                        credentialId,
                        userId)
                .stream()
                .findFirst()
                .orElseThrow(LlmCredentialService::notFound);
    }

    private static ApiException notFound() {
        return ApiException.notFound("credential_not_found", "Ключ не найден");
    }

    private static void validate(String label, String baseUrl, String model, String secret) {
        if (baseUrl == null || baseUrl.isBlank() || baseUrl.strip().length() > BASE_URL_MAX) {
            throw ApiException.badRequest("invalid_base_url", "Адрес провайдера обязателен");
        }
        if (model == null || model.isBlank() || model.strip().length() > MODEL_MAX) {
            throw ApiException.badRequest("invalid_model", "Название модели обязательно");
        }
        if (label != null && label.strip().length() > LABEL_MAX) {
            throw ApiException.badRequest("invalid_label", "Название ключа длиннее " + LABEL_MAX + " символов");
        }
        if (secret == null || secret.isBlank()) {
            throw ApiException.badRequest("invalid_secret", "Ключ провайдера обязателен");
        }
    }

    private static String normalizedLabel(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        return label.strip();
    }

    private static LlmCredential map(ResultSet rs, int rowNum) throws SQLException {
        return new LlmCredential(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("label"),
                rs.getString("base_url"),
                rs.getString("model"),
                rs.getBytes("secret_ciphertext"),
                rs.getBytes("secret_iv"),
                rs.getBytes("secret_tag"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
