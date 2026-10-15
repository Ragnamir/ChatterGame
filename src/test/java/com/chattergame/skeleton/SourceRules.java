package com.chattergame.skeleton;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Исполняемые формулировки глобальных инвариантов из `PLAN §2`. Каждый метод принимает
 * файл и возвращает нарушения, а не бросает исключение: тесты сами решают, где предел.
 *
 * Правила выражены как статически проверяемые конструкции, а не как намерения, потому что
 * их смысл — ловить регрессию архитектурных решений на каждом гейте.
 */
final class SourceRules {

    /**
     * Доменные таблицы: те, чьи строки нельзя удалять физически (`D-010`) и чьи мутации
     * обязаны оставлять событие (`D-033`). Список один на все правила намеренно: два
     * расходящихся перечня дали бы дыру, которую никто не заметит.
     */
    static final Set<String> DOMAIN_TABLES = Set.of(
            "game", "game_event", "membership", "chat", "transition", "transition_state",
            "character", "presence", "restriction", "storage", "item", "item_placement",
            "message", "inbox_entry", "read_cursor", "asset", "note", "note_keyword",
            "saved_message", "turn", "turn_budget", "request", "request_operation");

    /**
     * Узкое исключение из `D-010`, выданное решением `D-104`: орфан-сборка освобождает диск
     * удалением строк `asset` (`D-077`), и `DELETE FROM asset` живёт ровно в этом файле.
     * Исключение названо рядом с правилом, чтобы запрет и его граница читались вместе.
     */
    static final Path ASSET_PURGE =
            Paths.get("src/main/java/com/chattergame/media/AssetRepository.java");

    private static final List<String> EXTERNAL_CALL_TOKENS = List.of(
            "RestClient",
            "RestTemplate",
            "WebClient",
            "HttpClient",
            "HttpURLConnection",
            "LlmProvider",
            "JavaMailSender",
            "MailSender",
            "okhttp");

    private SourceRules() {
    }

    private static String alternation(Set<String> tables) {
        return String.join("|", tables.stream().sorted().toList());
    }

    /** Физическое удаление доменных строк: запрещено всегда (`D-010`), кроме `asset` (`D-104`). */
    static List<SourceScanner.Finding> physicalDeletions(Path file, Set<String> tables)
            throws IOException {
        SourceScanner scanner = SourceScanner.of(file);
        Pattern pattern = Pattern.compile(
                "(?is)\\b(?:DELETE\\s+FROM|TRUNCATE\\s+(?:TABLE\\s+)?)\\s*(?:"
                        + alternation(tables) + ")\\b");
        List<SourceScanner.Finding> findings = new ArrayList<>();
        for (SourceScanner.Literal literal : scanner.literals()) {
            Matcher matcher = pattern.matcher(literal.text());
            while (matcher.find()) {
                SourceScanner.Finding finding =
                        new SourceScanner.Finding(file, literal.line(), matcher.group());
                if (!isAllowedAssetPurge(file, finding)) {
                    findings.add(finding);
                }
            }
        }
        return findings;
    }

    /**
     * Граница исключения `D-104`: `DELETE FROM asset` допустим только в
     * {@link #ASSET_PURGE} и только по таблице `asset`. Оба условия обязательны — одного
     * пути мало, иначе в том же классе можно было бы удалить любую доменную строку.
     */
    static boolean isAllowedAssetPurge(Path file, SourceScanner.Finding finding) {
        return file.equals(ASSET_PURGE)
                && finding.text().toLowerCase(Locale.ROOT).contains("delete from asset");
    }

    /**
     * Журнал append-only (`REQUIREMENTS §12`): `UPDATE` без `WHERE` в нём недопустим, потому
     * что так незаметно правится вся история игры целиком.
     */
    static List<SourceScanner.Finding> unboundedUpdates(Path file, String table) throws IOException {
        SourceScanner scanner = SourceScanner.of(file);
        Pattern pattern = Pattern.compile(
                "(?is)\\bUPDATE\\s+" + Pattern.quote(table)
                        + "\\b(?![^;]*\\bWHERE\\b)[^;]*");
        List<SourceScanner.Finding> findings = new ArrayList<>();
        for (SourceScanner.Literal literal : scanner.literals()) {
            Matcher matcher = pattern.matcher(literal.text());
            while (matcher.find()) {
                findings.add(new SourceScanner.Finding(file, literal.line(), matcher.group()));
            }
        }
        return findings;
    }

    /**
     * Чтение видимости только через `inbox_entry` (`D-030`): SELECT, который берёт `message`
     * как источник строк и при этом не упоминает `inbox_entry`, обходит материализованную
     * видимость.
     *
     * Правило требует именно `SELECT`, а не любого `FROM message`: изменяющие операторы над
     * `message` ловит {@link #physicalDeletions} и `archived`-проверка. Смешение этих правил
     * даёт ложный адрес провала — разработчик ищет чтение, которого нет.
     */
    static List<SourceScanner.Finding> messageReadsWithoutInbox(Path file) throws IOException {
        SourceScanner scanner = SourceScanner.of(file);
        Pattern read = Pattern.compile("(?is)\\bSELECT\\b[^;]*?\\b(?:FROM|JOIN)\\s+message\\b");
        List<SourceScanner.Finding> findings = new ArrayList<>();
        for (SourceScanner.Literal literal : scanner.literals()) {
            Matcher matcher = read.matcher(literal.text());
            if (matcher.find()
                    && !literal.text().toLowerCase(Locale.ROOT).contains("inbox_entry")) {
                findings.add(new SourceScanner.Finding(file, literal.line(), literal.text()));
            }
        }
        return findings;
    }

    /** Блокирующие внешние вызовы внутри `@Transactional` запрещены (`D-044`). */
    static List<SourceScanner.Finding> externalCallsInTransactions(Path file) throws IOException {
        SourceScanner scanner = SourceScanner.of(file);
        List<SourceScanner.Finding> findings = new ArrayList<>();
        for (int[] range : scanner.bodyRangesAfter("@Transactional")) {
            String body = scanner.code().substring(range[0], range[1] + 1).toLowerCase(Locale.ROOT);
            for (String token : EXTERNAL_CALL_TOKENS) {
                if (body.contains(token.toLowerCase(Locale.ROOT))) {
                    findings.add(new SourceScanner.Finding(
                            file, scanner.lineOf(range[0]), token));
                }
            }
        }
        return findings;
    }

    /** Права проверяются на каждое событие, а не один раз при коннекте (`D-065`). */
    static List<SourceScanner.Finding> messageHandlersWithoutPermissionCheck(Path file)
            throws IOException {
        SourceScanner scanner = SourceScanner.of(file);
        if (!scanner.code().contains("handleMessage") && !scanner.code().contains("@MessageMapping")) {
            return List.of();
        }
        if (scanner.code().contains("assertCanPost")) {
            return List.of();
        }
        return List.of(new SourceScanner.Finding(
                file, 1, "обработчик сообщений без assertCanPost"));
    }

    /**
     * Любая мутация мира пишет событие в журнал. Форма проверки: сервис или контроллер,
     * выполняющий изменяющий SQL по доменной таблице, обязан упоминать `game_event`
     * (`AGENTS §2`, `D-033`). Репозитории исключены: изменяющий SQL живёт в них.
     */
    static List<SourceScanner.Finding> worldMutationsWithoutJournal(Path file, Set<String> tables)
            throws IOException {
        SourceScanner scanner = SourceScanner.of(file);
        boolean orchestrationLayer = scanner.code().contains("@Service")
                || scanner.code().contains("@Controller")
                || scanner.code().contains("@RestController");
        if (!orchestrationLayer) {
            return List.of();
        }
        Pattern mutation = Pattern.compile(
                "(?is)\\b(?:INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+(?:" + alternation(tables) + ")\\b");
        List<SourceScanner.Finding> findings = new ArrayList<>();
        for (SourceScanner.Literal literal : scanner.literals()) {
            Matcher matcher = mutation.matcher(literal.text());
            while (matcher.find()) {
                findings.add(new SourceScanner.Finding(file, literal.line(), matcher.group()));
            }
        }
        if (findings.isEmpty() || writesJournal(scanner)) {
            return List.of();
        }
        return findings;
    }

    /** Признак записи в журнал ищем в литералах: SQL лежит в них, а маска кода их скрывает. */
    private static boolean writesJournal(SourceScanner scanner) {
        return scanner.literals().stream()
                .anyMatch(literal -> literal.text().contains("game_event"));
    }

    /**
     * Совпадение ключевых слов считается при записи и в досчёте, но не в read-пути
     * (`D-050`, `D-087`). Правило выражено через вызывающего, а не через выражение: искать
     * его в чтении значило бы перебирать условия, а запрет — это свойство пути.
     *
     * <p>Файл признаётся read-путём, если объявляет читающую ручку (`@GetMapping`) или
     * называется `MessageQueryService` — единственный сервис чтения ленты. Совпадение в
     * таком файле считается нарушением, потому что подсветка уже материализована в
     * `matched_note_ids`, и пересчёт её на чтении разошёлся бы с записанным.
     */
    static List<SourceScanner.Finding> keywordMatchingInReadPath(Path file) throws IOException {
        SourceScanner scanner = SourceScanner.of(file);
        boolean matches = scanner.code().contains("KeywordMatcher")
                || scanner.code().contains("NoteMatchingService");
        if (!matches) {
            return List.of();
        }
        boolean readPath = scanner.code().contains("@GetMapping")
                || file.getFileName().toString().equals("MessageQueryService.java");
        if (!readPath) {
            return List.of();
        }
        return List.of(new SourceScanner.Finding(
                file, 1, "совпадение ключевых слов в read-пути"));
    }

    /**
     * Положительный контроль того же правила: подсветка обязана считаться хоть где-то, иначе
     * «в read-пути совпадения нет» выполняется на пустом месте. Проверяется отдельно, чтобы
     * инвариант нельзя было получить молчаливым удалением всей функциональности.
     */
    static boolean anyWritePathMatchesKeywords(java.util.List<Path> files) throws IOException {
        for (Path file : files) {
            SourceScanner scanner = SourceScanner.of(file);
            if (scanner.code().contains("KeywordMatcher.matches")
                    || scanner.code().contains("KeywordMatcher.storedForm")) {
                return true;
            }
        }
        return false;
    }
}
