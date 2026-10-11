package com.chattergame.skeleton;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Секреты не утекают в логи и не возвращаются API (`D-093`).
 *
 * Проверки статические и идут по замаскированному исходнику: SQL и сообщения лежат
 * в литералах, а обсуждение запретов — в комментариях, и разбор «по сырому тексту» давал бы
 * ложные срабатывания. Отдельно проверяется, что у типов ответа API нет полей секрета:
 * поле, которого нет, невозможно вернуть по невнимательности.
 */
class E02SecretsTest {

    private static final Path MAIN = Paths.get("src/main/java");
    private static final Path LLM_WEB = MAIN.resolve("com/chattergame/llm/web");
    private static final Path WEB = MAIN.resolve("com/chattergame/web");

    private static final Pattern LOGGER_CALL =
            Pattern.compile("\\blog\\.(?:trace|debug|info|warn|error)\\s*\\(");
    private static final Pattern RAW_CREDENTIAL = Pattern.compile("\\bLlmCredential\\b(?!View)");

    @Test
    void noLogStatementTouchesPasswordsOrSecrets() throws IOException {
        List<String> findings = new ArrayList<>();
        for (Path file : SourceScanner.javaFiles(MAIN)) {
            String code = SourceScanner.of(file).code();
            for (String call : logCalls(code)) {
                String lowered = call.toLowerCase(Locale.ROOT);
                if (lowered.contains("password") || lowered.contains("secret") || lowered.contains("hash")) {
                    findings.add(file + " -> " + call.strip());
                }
            }
        }

        assertThat(findings)
                .as("пароли и ключи LLM не пишутся в логи (D-093); "
                        + "значение в логе живёт дольше, чем инцидент")
                .isEmpty();
    }

    @Test
    void theRuleActuallyCatchesALoggedPassword() throws IOException {
        Path file = synthetic("""
                class Sample {
                    void bad(String password) {
                        log.info("пароль: {}", password);
                    }
                }
                """);

        String code = SourceScanner.of(file).code();
        List<String> calls = logCalls(code);

        assertThat(calls).as("детектор обязан срабатывать, иначе проверка мёртвая").hasSize(1);
        assertThat(calls.getFirst().toLowerCase(Locale.ROOT)).contains("password");
    }

    @Test
    void cipherNeverLogsAndNeverEchoesTheKey() throws IOException {
        Path file = MAIN.resolve("com/chattergame/llm/LlmSecretCipher.java");
        SourceScanner scanner = SourceScanner.of(file);

        assertThat(scanner.code())
                .as("шифр не логирует: значение ключа оказалось бы в файле логов")
                .doesNotContain("log.");
        assertThat(scanner.literals())
                .as("сообщения исключений не содержат значения ключа окружения")
                .noneMatch(literal -> literal.text().contains("encodedKey"));
    }

    @Test
    void apiViewOfCredentialHasNoSecretComponent() throws IOException {
        Path file = MAIN.resolve("com/chattergame/llm/LlmCredentialView.java");
        String code = SourceScanner.of(file).code();

        String components = code.substring(code.indexOf("record LlmCredentialView("));

        assertThat(components.toLowerCase(Locale.ROOT))
                .as("в типе ответа нет ни секрета, ни шифртекста (D-093)")
                .doesNotContain("secret")
                .doesNotContain("cipher");
    }

    @Test
    void llmControllersNeverTouchRawCredentialOrCipher() throws IOException {
        List<String> findings = new ArrayList<>();
        for (Path file : SourceScanner.javaFiles(LLM_WEB)) {
            String code = SourceScanner.of(file).code();
            if (RAW_CREDENTIAL.matcher(code).find()) {
                findings.add(file + " -> LlmCredential");
            }
            if (code.contains("LlmSecretCipher")) {
                findings.add(file + " -> LlmSecretCipher");
            }
        }

        assertThat(findings)
                .as("контроллер видит только LlmCredentialView: путь утечки не должен существовать "
                        + "раньше, чем понадобится")
                .isEmpty();
    }

    @Test
    void httpLayerNeverMentionsSecretHash() throws IOException {
        List<String> findings = new ArrayList<>();
        List<Path> webPackages = new ArrayList<>(SourceScanner.javaFiles(WEB));
        webPackages.addAll(SourceScanner.javaFiles(LLM_WEB));
        for (Path file : webPackages) {
            if (SourceScanner.of(file).code().contains("secretHash")) {
                findings.add(file.toString());
            }
        }

        assertThat(findings)
                .as("слой HTTP не должен знать про колонку с хешем пароля")
                .isEmpty();
    }

    /** Вызовы логгера с сбалансированными скобками: аргументы важнее имени метода. */
    private static List<String> logCalls(String code) {
        List<String> calls = new ArrayList<>();
        var matcher = LOGGER_CALL.matcher(code);
        while (matcher.find()) {
            int open = code.indexOf('(', matcher.start());
            int depth = 0;
            for (int i = open; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        calls.add(code.substring(matcher.start(), i + 1));
                        break;
                    }
                }
            }
        }
        return calls;
    }

    private static final Path TMP = Paths.get("build/e02-secrets");

    private static Path synthetic(String source) throws IOException {
        java.nio.file.Files.createDirectories(TMP);
        Path file = TMP.resolve("Sample.java");
        java.nio.file.Files.writeString(file, source);
        return file;
    }
}
