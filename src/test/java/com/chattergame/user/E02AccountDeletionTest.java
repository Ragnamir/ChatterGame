package com.chattergame.user;

import com.chattergame.util.Uuids;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Удаление аккаунта (`D-095`): блокировка входа плюс анонимизация PII. Строка пользователя
 * остаётся, потому что на неё ссылаются персонажи и сообщения: физическое удаление сделало бы
 * транскрипты игр битыми (`D-010`).
 *
 * Игры удалённого мастера архивируются и остаются читаемыми участникам, а не исчезают:
 * передача мастерства запрещена (`D-021`), значит заменить мастера нечем.
 */
class E02AccountDeletionTest extends E02TestSupport {

    private record Fixture(String login, UUID userId, UUID gameId, UUID chatId, UUID messageId) {
    }

    /** Игра, чат и сообщение мастера: их судьба и есть предмет проверки. */
    private Fixture masterWithWorld(String login) throws Exception {
        String token = registerAndGetToken(login, PASSWORD);
        UUID userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE login_normalized = ?", UUID.class, loginNormalized(login));

        UUID gameId = Uuids.v7();
        jdbc.update(
                "INSERT INTO game (id, master_user_id, title) VALUES (?, ?, 'Игра мастера')",
                gameId,
                userId);

        UUID chatId = Uuids.v7();
        jdbc.update(
                "INSERT INTO chat (id, game_id, kind, title) VALUES (?, ?, 'LOCATION', 'Таверна')",
                chatId,
                gameId);

        UUID messageId = Uuids.v7();
        jdbc.update(
                "INSERT INTO message (id, game_id, chat_id, seq, kind, author_user_id, body) "
                        + "VALUES (?, ?, ?, 1, 'SPEECH', ?, 'Я ухожу')",
                messageId,
                gameId,
                chatId,
                userId);

        jdbc.update(
                "INSERT INTO llm_credential (id, user_id, label, base_url, model, secret_ciphertext, "
                        + "secret_iv, secret_tag, active) VALUES (?, ?, 'ключ', ?, ?, ?, ?, ?, true)",
                Uuids.v7(),
                userId,
                "https://openrouter.ai/api/v1",
                "qwen/qwen3-32b",
                new byte[] {1, 2, 3},
                new byte[12],
                new byte[16]);
        jdbc.update(
                "INSERT INTO password_reset_token (id, user_id, token_hash, expires_at) "
                        + "VALUES (?, ?, ?, now() + interval '1 hour')",
                Uuids.v7(),
                userId,
                Tokens.hash(Uuids.v7().toString()));

        return new Fixture(login, userId, gameId, chatId, messageId);
    }

    @Test
    void deletionBlocksLoginAndAnonymizesPii() throws Exception {
        Fixture fixture = masterWithWorld(unique("Удаляемый"));

        assertThat(deleteAccount(fixture.login(), PASSWORD)).isEqualTo(204);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT login, login_normalized, display_name, email, email_verified_at, status "
                        + "FROM app_user WHERE id = ?",
                fixture.userId());

        assertThat(row.get("status")).isEqualTo(AppUser.STATUS_DELETED);
        assertThat((String) row.get("login")).startsWith(AccountDeletionService.ANONYMIZED_LOGIN_PREFIX);
        assertThat(row.get("email")).isNull();
        assertThat(row.get("email_verified_at")).isNull();
        assertThat(row.get("display_name")).isEqualTo(AccountDeletionService.ANONYMIZED_DISPLAY_NAME);
        assertThat(loginRequest(fixture.login(), PASSWORD).getResponse().getStatus())
                .as("вход по прежнему логину закрыт")
                .isEqualTo(401);
        assertThat(jdbc.queryForObject(
                        "SELECT secret_hash FROM auth_identity WHERE user_id = ?",
                        String.class,
                        fixture.userId()))
                .as("пароль удалён вместе с креденшалом")
                .isNull();
    }

    @Test
    void deletionRequiresTheCorrectPassword() throws Exception {
        Fixture fixture = masterWithWorld(unique("ПарольНужен"));

        var wrong = call(delete("/me"), tokenFor(fixture.login()), Map.of("password", OTHER_PASSWORD));
        var anonymous = call(delete("/me"), null, Map.of("password", PASSWORD));

        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app_user WHERE id = ?", String.class, fixture.userId()))
                .as("без верного пароля аккаунт остаётся активным")
                .isEqualTo(AppUser.STATUS_ACTIVE);
    }

    @Test
    void deletionRemovesSessionsTokensAndCredentials() throws Exception {
        Fixture fixture = masterWithWorld(unique("Доступы"));
        String token = tokenFor(fixture.login());
        loginAndGetToken(fixture.login(), PASSWORD);

        assertThat(call(delete("/me"), token, Map.of("password", PASSWORD)).getResponse().getStatus())
                .isEqualTo(204);

        assertThat(countRows("SELECT count(*) FROM session WHERE user_id = ?", fixture.userId()))
                .as("сессии закрытого аккаунта не должны существовать")
                .isZero();
        assertThat(countRows(
                        "SELECT count(*) FROM password_reset_token WHERE user_id = ?", fixture.userId()))
                .isZero();
        assertThat(countRows("SELECT count(*) FROM llm_credential WHERE user_id = ?", fixture.userId()))
                .as("ключ LLM — это доступ, а не часть игрового мира")
                .isZero();
        assertThat(call(get("/me"), token, null).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void deletionKeepsMessagesAndCharacters() throws Exception {
        Fixture fixture = masterWithWorld(unique("МирЖив"));
        UUID characterId = Uuids.v7();
        jdbc.update(
                "INSERT INTO character (id, game_id, name) VALUES (?, ?, 'Персонаж')",
                characterId,
                fixture.gameId());

        deleteAccount(fixture.login(), PASSWORD);

        assertThat(jdbc.queryForObject(
                        "SELECT body FROM message WHERE id = ?", String.class, fixture.messageId()))
                .as("сообщение мастера остаётся читаемым (D-095)")
                .isEqualTo("Я ухожу");
        assertThat(countRows("SELECT count(*) FROM character WHERE id = ?", characterId))
                .as("персонажи не удаляются: на них ссылается транскрипт")
                .isEqualTo(1);
        assertThat(countRows(
                        "SELECT count(*) FROM message WHERE game_id = ?", fixture.gameId()))
                .isEqualTo(1);
    }

    @Test
    void masterGamesAreArchivedWithReasonAndEvent() throws Exception {
        Fixture fixture = masterWithWorld(unique("Архив"));
        String token = tokenFor(fixture.login());

        assertThat(call(delete("/me"), token, Map.of("password", PASSWORD)).getResponse().getStatus())
                .isEqualTo(204);

        Map<String, Object> game = jdbc.queryForMap(
                "SELECT archived, archived_reason FROM game WHERE id = ?", fixture.gameId());
        assertThat(game.get("archived")).isEqualTo(true);
        assertThat(game.get("archived_reason")).isEqualTo(AccountDeletionService.ARCHIVE_REASON);

        assertThat(countRows(
                        "SELECT count(*) FROM game_event WHERE game_id = ? AND type = 'GAME_ARCHIVED'",
                        fixture.gameId()))
                .as("архивация — мутация мира, значит обязана оставить событие (D-033)")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT seq FROM game_event WHERE game_id = ? AND type = 'GAME_ARCHIVED'",
                        Long.class,
                        fixture.gameId()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "SELECT payload::text FROM game_event WHERE game_id = ? AND type = 'GAME_ARCHIVED'",
                        String.class,
                        fixture.gameId()))
                .contains(AccountDeletionService.ARCHIVE_REASON)
                .contains(fixture.userId().toString());
        assertThat(jdbc.queryForObject(
                        "SELECT last_seq FROM game_stream WHERE game_id = ?", Long.class, fixture.gameId()))
                .as("счётчик журнала сдвинут вместе с событием")
                .isEqualTo(1L);
    }

    @Test
    void archivingContinuesTheExistingJournalSequence() throws Exception {
        Fixture fixture = masterWithWorld(unique("Сквозная"));
        jdbc.update(
                "INSERT INTO game_stream (game_id, last_seq) VALUES (?, 7)", fixture.gameId());
        jdbc.update(
                "INSERT INTO game_event (id, game_id, seq, type, payload) "
                        + "VALUES (?, ?, 7, 'GAME_FINISHED', '{}'::jsonb)",
                Uuids.v7(),
                fixture.gameId());

        deleteAccount(fixture.login(), PASSWORD);

        assertThat(jdbc.queryForObject(
                        "SELECT seq FROM game_event WHERE game_id = ? AND type = 'GAME_ARCHIVED'",
                        Long.class,
                        fixture.gameId()))
                .as("номер выдаётся под блокировкой счётчика, а не считается по count(*)")
                .isEqualTo(8L);
        assertThat(jdbc.queryForObject(
                        "SELECT last_seq FROM game_stream WHERE game_id = ?", Long.class, fixture.gameId()))
                .isEqualTo(8L);
        assertThat(countRows("SELECT count(*) FROM game_event WHERE game_id = ?", fixture.gameId()))
                .as("история не переписывается: прежнее событие на месте")
                .isEqualTo(2);
    }

    @Test
    void deletedAccountIsNotFoundOnLoginByOldDisplayName() throws Exception {
        Fixture fixture = masterWithWorld(unique("Бывший"));
        deleteAccount(fixture.login(), PASSWORD);

        assertThat(countRows(
                        "SELECT count(*) FROM app_user WHERE login_normalized = ?",
                        loginNormalized(fixture.login())))
                .as("прежний логин больше ни на кого не указывает")
                .isZero();
    }

    @Test
    void resetPasswordOfDeletedAccountIsRefused() throws Exception {
        Fixture fixture = masterWithWorld(unique("СбросУдалён"));
        String email = unique("почта") + "@example.org";
        jdbc.update(
                "UPDATE app_user SET email = ?, email_verified_at = now() WHERE id = ?",
                email,
                fixture.userId());
        deleteAccount(fixture.login(), PASSWORD);

        var requested = call(post("/me/password/reset"), null, Map.of("email", email));

        assertThat(requested.getResponse().getStatus())
                .as("ответ остаётся одинаковым, письма закрытому аккаунту не будет")
                .isEqualTo(202);
        assertThat(countRows(
                        "SELECT count(*) FROM password_reset_token WHERE user_id = ?", fixture.userId()))
                .isZero();
    }

    private int deleteAccount(String login, String password) throws Exception {
        return call(delete("/me"), tokenFor(login), Map.of("password", password))
                .getResponse()
                .getStatus();
    }

    /** Свежий вход: тест не должен зависеть от того, какой токен выдал предыдущий вызов. */
    private String tokenFor(String login) throws Exception {
        return loginAndGetToken(login, PASSWORD);
    }
}
