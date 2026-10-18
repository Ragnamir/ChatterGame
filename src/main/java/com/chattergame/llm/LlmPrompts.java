package com.chattergame.llm;

import com.chattergame.request.RequestType;

import java.util.List;

/**
 * Промпты со-мастера и автопилота (`REQUIREMENTS §18`). Вынесены из сервиса, потому что
 * это текст, а не оркестрация: его правят при смене модели, и он обязан читаться целиком,
 * без ветвей вызова вокруг.
 *
 * <p>Оба промпта требуют один JSON-объект и не разрешают пояснений: ответ проходит через
 * тот же разбор change set, что и заявка игрока (`D-041`), и «модель написала прозу» — это
 * `FAILED`, а не повод угадывать (`D-043`).
 *
 * <p>Контекст собирается иерархически (`D-045`): сначала правила, затем сводка мира, затем
 * последние реплики. Полный транскрипт месячной игры в окно не влезает, поэтому «все тексты»
 * здесь означает «сжатая сводка плюс хвост», а не выгрузку.
 */
final class LlmPrompts {

    private LlmPrompts() {
    }

    /** Что модели можно и чего нельзя: общее для обоих видов вызова. */
    private static final String RULES = """
            Ты помогаешь мастеру словесной (текстовой) игры. Игра идёт в чатах-локациях,
            у каждого персонажа есть карточка: имя, внешность, открытая и закрытая история.
            Ты не ведёшь игру сам и не обращаешься к игрокам: ты предлагаешь правки, которые
            мастер одобрит, изменит или отклонит.

            Правила:
            - Пиши по-русски, в стиле и времени мира игры.
            - Не придумывай новых персонажей, локаций и предметов, которых нет в сводке.
            - Не пересказывай закрытые истории и личные разговоры: их тебе не показывают.
            - Ответ — один JSON-объект и ничего больше: без пояснений, без markdown.
            """;

    static String system(RequestType type) {
        return RULES + (type == RequestType.AUTOPILOT ? autopilotTask() : coMasterTask());
    }

    private static String coMasterTask() {
        return """
                Задача: предложить правки мира по запросу мастера.

                Формат ответа:
                {"operations": [{"op": "<вид>", "payload": {...}}]}

                Допустимые виды операций:
                - update_character_field: {"characterId": "<uuid>", "field": "appearance|publicHistory|privateHistory|name", "value": "<текст>"}
                - post_master_message: {"chatId": "<uuid>", "body": "<текст до 4000 знаков>"}
                - create_note: {"body": "<текст до 2000 знаков>", "targetKind": "CHARACTER|MEMBERSHIP", "targetId": "<uuid>"}
                - move_character: {"characterId": "<uuid>", "transitionId": "<uuid>"}

                Бери идентификаторы только из сводки. Если правка не нужна, верни
                {"operations": []} — мастер увидит, что предложений нет.
                """;
    }

    private static String autopilotTask() {
        return """
                Задача: сыграть за отсутствующего персонажа — одну реплику или одно действие.

                Формат ответа:
                {"operations": [{"op": "post_character_message", "payload": {"chatId": "<uuid>", "characterId": "<uuid>", "kind": "SPEECH|ACTION", "body": "<текст до 4000 знаков>"}}]}

                Пиши от лица названного персонажа, коротко и в жанре сцены. Не описывай
                чужие мысли и не решай за других игроков. Если персонаж не может говорить
                или действовать в этой сцене, верни {"operations": []}.
                """;
    }

    /**
     * Запрос мастера и сводка мира. Разделы подписаны, потому что модель увереннее держится
     * формата, когда видит, где кончаются правила и начинаются данные.
     */
    static String user(
            RequestType type,
            String instruction,
            String gameTitle,
            String phase,
            List<String> characters,
            List<String> notes,
            List<String> transcript) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Игра: ").append(nullSafe(gameTitle)).append('\n');
        prompt.append("Фаза: ").append(nullSafe(phase)).append("\n\n");
        if (!characters.isEmpty()) {
            prompt.append("Персонажи:\n");
            characters.forEach(line -> prompt.append("- ").append(line).append('\n'));
            prompt.append('\n');
        }
        if (!notes.isEmpty()) {
            prompt.append("Заметки, доступные этому вызову:\n");
            notes.forEach(line -> prompt.append("- ").append(line).append('\n'));
            prompt.append('\n');
        }
        if (!transcript.isEmpty()) {
            prompt.append("Последние реплики:\n");
            transcript.forEach(line -> prompt.append(line).append('\n'));
            prompt.append('\n');
        }
        prompt.append(type == RequestType.AUTOPILOT ? "Сцена и указание мастера:\n" : "Запрос мастера:\n");
        prompt.append(nullSafe(instruction));
        return prompt.toString();
    }

    private static String nullSafe(String value) {
        return value == null || value.isBlank() ? "—" : value.strip();
    }
}
