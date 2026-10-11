package com.chattergame.llm;

import com.chattergame.user.E02TestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Ключи LLM (`D-093`): несколько на пользователя, один активный, секрет шифруется AES-GCM
 * ключом из секрета окружения, в API уходит маской и в ответе отсутствует как поле.
 *
 * Ключ окружения задаётся тестом через `@DynamicPropertySource`: без него хранение обязано
 * отказывать, и это проверяется отдельно ниже. Значение — 32 байта в base64, как требует
 * {@link LlmSecretCipher}.
 */
class E02LlmCredentialTest extends E02TestSupport {

    private static final byte[] ENV_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String ENV_KEY_BASE64 = Base64.getEncoder().encodeToString(ENV_KEY);
    private static final String SECRET = "sk-секрет-который-нельзя-видеть-f3a9";

    @DynamicPropertySource
    static void llmKey(DynamicPropertyRegistry registry) {
        registry.add("chattergame.llm.secret-key", () -> ENV_KEY_BASE64);
    }

    private String createCredential(String token, String label, String secret) throws Exception {
        var result = call(
                post("/me/llm-credentials"),
                token,
                Map.of(
                        "label", label,
                        "baseUrl", "https://openrouter.ai/api/v1",
                        "model", "qwen/qwen3-32b",
                        "secret", secret));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return json.readTree(bodyOf(result)).get("id").asText();
    }

    private Row rowOf(String login) {
        return jdbc.queryForObject(
                "SELECT secret_ciphertext, secret_iv, secret_tag FROM llm_credential WHERE user_id = "
                        + "(SELECT id FROM app_user WHERE login_normalized = ?)",
                (rs, rowNum) -> new Row(
                        rs.getBytes("secret_ciphertext"), rs.getBytes("secret_iv"), rs.getBytes("secret_tag")),
                loginNormalized(login));
    }

    private record Row(byte[] ciphertext, byte[] iv, byte[] tag) {
    }

    @Test
    void secretIsEncryptedAtRestAndDecryptableOnlyWithEnvironmentKey() throws Exception {
        String login = unique("КлючАтРест");
        String token = registerAndGetToken(login, PASSWORD);
        createCredential(token, "личный", SECRET);

        Row row = rowOf(login);

        assertThat(new String(row.ciphertext(), StandardCharsets.UTF_8))
                .as("в колонке не должно быть открытого ключа")
                .doesNotContain(SECRET);
        assertThat(row.ciphertext())
                .as("шифртекст не совпадает с открытым текстом")
                .isNotEqualTo(SECRET.getBytes(StandardCharsets.UTF_8));
        assertThat(row.iv()).hasSize(12);
        assertThat(row.tag()).as("тег GCM — 128 бит").hasSize(16);

        String decrypted = new LlmSecretCipher(ENV_KEY_BASE64)
                .decrypt(row.ciphertext(), row.iv(), row.tag());
        assertThat(decrypted).as("иначе ключ потерян безвозвратно").isEqualTo(SECRET);
    }

    @Test
    void sameSecretEncryptsDifferentlyEachTime() throws Exception {
        String token = registerAndGetToken(unique("РазныеШифры"), PASSWORD);
        String first = createCredential(token, "первый", SECRET);
        String second = createCredential(token, "второй", SECRET);

        assertThat(cipherHexOf(first))
                .as("случайный IV на каждое шифрование: одинаковый ключ не виден по совпадению шифртекста")
                .isNotEqualTo(cipherHexOf(second));
    }

    private String cipherHexOf(String credentialId) {
        return jdbc.queryForObject(
                "SELECT encode(secret_ciphertext, 'hex') FROM llm_credential WHERE id = ?",
                String.class,
                UUID.fromString(credentialId));
    }

    @Test
    void apiNeverReturnsTheSecret() throws Exception {
        String token = registerAndGetToken(unique("Маска"), PASSWORD);
        createCredential(token, "маскируемый", SECRET);

        String listed = bodyOf(call(get("/me/llm-credentials"), token, null));

        assertThat(listed).doesNotContain(SECRET);
        JsonNode first = json.readTree(listed).get(0);
        assertThat(fieldNames(first))
                .as("поля секрета нет в ответе вообще: его нельзя вернуть по невнимательности")
                .doesNotContain("secret", "secretHash", "secretCiphertext", "secretTag");
        assertThat(first.get("baseUrl").asText()).isEqualTo("https://openrouter.ai/api/v1");
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }

    @Test
    void onlyOneCredentialIsActiveAtATime() throws Exception {
        String login = unique("Активный");
        String token = registerAndGetToken(login, PASSWORD);
        String first = createCredential(token, "первый", "ключ-1");
        String second = createCredential(token, "второй", "ключ-2");

        call(post("/me/llm-credentials/" + second + "/activate"), token, null);
        assertThat(activeCount(login)).isEqualTo(1);
        assertThat(isActive(second)).isTrue();

        call(post("/me/llm-credentials/" + first + "/activate"), token, null);
        assertThat(activeCount(login))
                .as("активация снимает флаг с прежнего ключа")
                .isEqualTo(1);
        assertThat(isActive(first)).isTrue();
        assertThat(isActive(second)).isFalse();
    }

    private boolean isActive(String credentialId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT active FROM llm_credential WHERE id = ?", Boolean.class, UUID.fromString(credentialId)));
    }

    @Test
    void createdCredentialIsNotActiveByDefault() throws Exception {
        String token = registerAndGetToken(unique("НеАктивный"), PASSWORD);
        createCredential(token, "новый", "ключ-3");

        JsonNode listed = json.readTree(bodyOf(call(get("/me/llm-credentials"), token, null)));

        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("active").asBoolean()).isFalse();
    }

    @Test
    void credentialsAreScopedToTheirOwner() throws Exception {
        String ownerToken = registerAndGetToken(unique("Владелец"), PASSWORD);
        String credentialId = createCredential(ownerToken, "чужой-не-тронет", "ключ-4");
        String strangerToken = registerAndGetToken(unique("Чужой"), PASSWORD);

        var activated =
                call(post("/me/llm-credentials/" + credentialId + "/activate"), strangerToken, null);
        var deleted = call(delete("/me/llm-credentials/" + credentialId), strangerToken, null);
        var listed = json.readTree(bodyOf(call(get("/me/llm-credentials"), strangerToken, null)));

        assertThat(activated.getResponse().getStatus()).isEqualTo(404);
        assertThat(deleted.getResponse().getStatus()).isEqualTo(404);
        assertThat(listed).isEmpty();
        assertThat(countRows(
                        "SELECT count(*) FROM llm_credential WHERE id = ?", UUID.fromString(credentialId)))
                .as("чужой запрос ничего не изменил")
                .isEqualTo(1);
    }

    @Test
    void credentialsRequireSession() throws Exception {
        assertThat(call(get("/me/llm-credentials"), null, null).getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void invalidCredentialInputIsRejected() throws Exception {
        String token = registerAndGetToken(unique("ПлохойВвод"), PASSWORD);

        var withoutBaseUrl = call(
                post("/me/llm-credentials"), token, Map.of("baseUrl", "  ", "model", "m", "secret", "s"));
        var withoutModel = call(
                post("/me/llm-credentials"), token,
                Map.of("baseUrl", "https://x.example.org", "model", "", "secret", "s"));
        var withoutSecret = call(
                post("/me/llm-credentials"), token,
                Map.of("baseUrl", "https://x.example.org", "model", "m", "secret", ""));

        assertThat(withoutBaseUrl.getResponse().getStatus()).isEqualTo(400);
        assertThat(withoutModel.getResponse().getStatus()).isEqualTo(400);
        assertThat(withoutSecret.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void deletingCredentialRemovesIt() throws Exception {
        String token = registerAndGetToken(unique("Удаление"), PASSWORD);
        String credentialId = createCredential(token, "лишний", "ключ-5");

        assertThat(call(delete("/me/llm-credentials/" + credentialId), token, null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(204);
        assertThat(countRows(
                        "SELECT count(*) FROM llm_credential WHERE id = ?", UUID.fromString(credentialId)))
                .isZero();
    }

    @Test
    void missingEnvironmentKeyRefusesToStoreSecrets() {
        LlmSecretCipher withoutKey = new LlmSecretCipher("");

        assertThatThrownBy(() -> withoutKey.encrypt("ключ"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chattergame.llm.secret-key");
    }

    @Test
    void wrongKeyLengthIsRejected() {
        String shortKey =
                Base64.getEncoder().encodeToString("короткий".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new LlmSecretCipher(shortKey).encrypt("ключ"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void roundTripWorksWithProperKey() {
        LlmSecretCipher cipher = new LlmSecretCipher(ENV_KEY_BASE64);

        LlmSecretCipher.Sealed sealed = cipher.encrypt("ключ-для-проверки");

        assertThat(cipher.decrypt(sealed.ciphertext(), sealed.iv(), sealed.tag()))
                .isEqualTo("ключ-для-проверки");
    }

    private long activeCount(String login) {
        return countRows(
                "SELECT count(*) FROM llm_credential WHERE active AND user_id = "
                        + "(SELECT id FROM app_user WHERE login_normalized = ?)",
                loginNormalized(login));
    }
}
