package com.chattergame.message;

import com.chattergame.chat.ChatKind;
import com.chattergame.game.MembershipRole;
import com.chattergame.game.Phase;
import com.chattergame.restriction.RestrictionKind;
import com.chattergame.util.ApiException;

import java.util.Set;
import java.util.UUID;

/**
 * Матрица прав на запись (`REQUIREMENTS §11`) — единственный `assertCanPost` (`D-065`).
 *
 * <p>Право проверяется на каждое событие, а не при установке соединения: сокет живёт часами,
 * и за это время персонажа снимают, мьютят, а игру завершают (`D-065`). Поэтому вход — это
 * снимок состояния {@link Posting}, снятый из базы в момент записи, а не ссылка на сервис.
 * Снимок же позволяет проверить матрицу целиком табличным тестом, включая отрицательные
 * клетки, без базы и HTTP: правило читается как таблица, а не как набор ветвей по коду.
 */
public final class MessageAccess {

    private MessageAccess() {
    }

    /**
     * Всё, от чего зависит право записи.
     *
     * <p>`characterId` заполнен только там, где автор — персонаж: `SPEECH`, `ACTION`, а также
     * `MASTER` от лица персонажа (`D-004`). У `JOKE` автор — участие (`D-099`), у `MASTER` без
     * персонажа — мастер, у `SYSTEM` автора нет вовсе.
     *
     * <p>`presentInChat` — активное присутствие персонажа в этом чате, а не «персонаж есть
     * в игре»: отсутствующий не читает и не пишет (`REQUIREMENTS §7.1`).
     */
    public record Posting(
            MembershipRole role,
            boolean joined,
            boolean banned,
            UUID actorMembershipId,
            UUID characterId,
            UUID occupantMembershipId,
            boolean characterArchived,
            ChatKind chatKind,
            UUID chatId,
            UUID finalChatId,
            Phase phase,
            boolean chatArchived,
            boolean presentInChat,
            Set<RestrictionKind> characterRestrictions,
            Set<RestrictionKind> membershipRestrictions) {
    }

    /**
     * Разрешает запись или бросает {@link ApiException} с машинным кодом. Порядок проверок:
     * участие, тип сообщения, тип чата, затем автор и запреты. Отказ называет самую общую
     * причину первой, чтобы клиент не выпытывал состояние мира отрицательными ответами.
     */
    public static void assertCanPost(Posting posting, MessageKind kind) {
        if (posting.banned()) {
            throw ApiException.forbidden("membership_banned", "Участие в этой игре запрещено мастером");
        }
        if (!posting.joined()) {
            throw ApiException.forbidden("membership_not_joined", "Участие в этой игре не активно");
        }
        if (kind == null) {
            throw ApiException.badRequest("message_kind_required", "Укажите тип сообщения");
        }
        if (kind == MessageKind.SYSTEM) {
            throw ApiException.badRequest(
                    "system_is_generated", "Системные события генерирует система, а не игрок");
        }
        if (posting.chatArchived()) {
            throw ApiException.conflict("chat_archived", "Чат выведен из игры: писать в него нельзя");
        }
        assertKindAllowedInChat(posting, kind);
        assertFinishedRules(posting, kind);

        switch (kind) {
            case SPEECH -> assertSpeech(posting);
            case ACTION -> assertAction(posting);
            case JOKE -> assertJoke(posting);
            case MASTER -> assertMaster(posting);
            case SYSTEM -> throw ApiException.badRequest(
                    "system_is_generated", "Системные события генерирует система, а не игрок");
        }
    }

    /**
     * Ограничения типа чата (`REQUIREMENTS §11`): в радиочате только речь, в финальном
     * обсуждении только шутка. Проверка стоит до проверки автора, потому что это свойство
     * чата, а не человека.
     */
    private static void assertKindAllowedInChat(Posting posting, MessageKind kind) {
        if (posting.chatKind() == ChatKind.RADIO && kind != MessageKind.SPEECH) {
            throw ApiException.badRequest(
                    "radio_speech_only", "В радиочате разрешена только речь");
        }
        if (posting.chatKind() == ChatKind.FINAL
                && kind != MessageKind.JOKE
                && !(kind == MessageKind.MASTER && posting.phase() == Phase.FINISHED)) {
            throw ApiException.badRequest(
                    "final_joke_only", "В чате финального обсуждения разрешены только шутки");
        }
        if (posting.chatKind() == ChatKind.MASTER_DM
                && kind != MessageKind.SPEECH
                && kind != MessageKind.JOKE) {
            throw ApiException.badRequest(
                    "master_dm_speech_only", "В личном чате с мастером — только речь и шутки");
        }
    }

    /**
     * После `FINISHED` мир read-only (`D-094`): писать можно только в чат финального
     * обсуждения и только `JOKE` или `MASTER`. Проверка идёт по паре «фаза + чат», а не по
     * фазе одной: сам чат обсуждения существует и до конца игры, но правила в нём те же.
     */
    private static void assertFinishedRules(Posting posting, MessageKind kind) {
        if (posting.phase() != Phase.FINISHED) {
            return;
        }
        boolean finalChat = posting.finalChatId() != null && posting.finalChatId().equals(posting.chatId());
        if (!finalChat) {
            throw ApiException.conflict(
                    "game_finished", "Игра завершена: мир не меняется, доступно только финальное обсуждение");
        }
        if (kind != MessageKind.JOKE && kind != MessageKind.MASTER) {
            throw ApiException.conflict(
                    "final_chat_joke_only", "В финальном обсуждении допустимы шутка и реплика мастера");
        }
    }

    /** `SPEECH`: своя занятая роль у игрока, любой присутствующий персонаж у мастера (`D-004`). */
    private static void assertSpeech(Posting posting) {
        assertCharacterAuthor(posting);
        if (posting.characterRestrictions().contains(RestrictionKind.NO_SPEECH)) {
            throw ApiException.forbidden("speech_restricted", "Персонажу запрещена речь");
        }
    }

    private static void assertAction(Posting posting) {
        assertCharacterAuthor(posting);
        if (posting.characterRestrictions().contains(RestrictionKind.NO_ACTION)) {
            throw ApiException.forbidden("action_restricted", "Персонажу запрещено действие");
        }
    }

    /**
     * Общая часть `SPEECH` и `ACTION`. Наблюдателю недоступны оба (`D-063`); игрок пишет
     * только от своего занятого персонажа (`D-100`), мастер — от любого присутствующего.
     */
    private static void assertCharacterAuthor(Posting posting) {
        if (posting.role() == MembershipRole.OBSERVER) {
            throw ApiException.forbidden(
                    "observer_cannot_post", "Наблюдатель читает игру, а не пишет в неё");
        }
        if (posting.characterId() == null) {
            throw ApiException.badRequest(
                    "character_required", "Укажите персонажа, от чьего лица говорите");
        }
        if (posting.characterArchived()) {
            throw ApiException.conflict("character_archived", "Персонаж выведен из игры");
        }
        if (posting.role() == MembershipRole.PLAYER
                && (posting.occupantMembershipId() == null
                        || !posting.actorMembershipId().equals(posting.occupantMembershipId()))) {
            throw ApiException.forbidden("not_your_character", "Этот персонаж занят не вами");
        }
        if (posting.role() == MembershipRole.PLAYER && posting.phase() != Phase.RUNNING) {
            throw ApiException.conflict(
                    "game_not_running", "Игра не идёт: говорить от лица персонажа нельзя");
        }
        if (!posting.presentInChat()) {
            throw ApiException.conflict(
                    "character_not_present", "Персонаж в этом чате не присутствует");
        }
    }

    /**
     * `JOKE` — метагейм: пишется от участия в любой читаемый чат, присутствие не требуется
     * (`D-099`), запрет `NO_JOKE` целится в участие, а не в персонажа. Наблюдателю шутка
     * доступна только в финальном обсуждении (`REQUIREMENTS §11`).
     */
    private static void assertJoke(Posting posting) {
        if (posting.role() == MembershipRole.OBSERVER) {
            if (posting.chatKind() != ChatKind.FINAL) {
                throw ApiException.forbidden(
                        "observer_cannot_post", "Наблюдатель шутит только в финальном обсуждении");
            }
            return;
        }
        if (posting.membershipRestrictions().contains(RestrictionKind.NO_JOKE)) {
            throw ApiException.forbidden("joke_restricted", "Этому игроку запрещены шутки");
        }
    }

    /** `MASTER`: событие мира в любом чате игры, только от мастера. */
    private static void assertMaster(Posting posting) {
        if (posting.role() != MembershipRole.MASTER) {
            throw ApiException.forbidden("not_master", "Событие мира пишет только мастер");
        }
    }

    /** Лимит длины тела (`D-098`); проверяется здесь, потому что отказ несёт машинный код. */
    public static void assertBodyLength(String body) {
        if (body != null && body.strip().length() > Message.BODY_MAX) {
            throw ApiException.badRequest(
                    "message_too_long", "Сообщение: до " + Message.BODY_MAX + " знаков");
        }
    }
}
