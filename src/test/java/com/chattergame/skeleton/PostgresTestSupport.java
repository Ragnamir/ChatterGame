package com.chattergame.skeleton;

import com.chattergame.tools.LiquibaseRunner;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Единственный источник тестовой БД.
 *
 * Порядок поиска: переменные окружения `CHATTERGAME_DB_*` (их выставляет гейт на специально
 * созданной чистой базе), затем локальная база из `docker-compose`, затем Testcontainers.
 * Проверки идут реальным Postgres 16 (`D-092`), а не H2: диалект, индексы и ограничения
 * в `001-baseline.yaml` специфичны для PostgreSQL.
 *
 * Если ни один источник недоступен, тесты на БД пропускаются через assumption — см.
 * {@link #isAvailable()}.
 */
final class PostgresTestSupport {

    static final String ENV_URL = "CHATTERGAME_DB_URL";
    static final String ENV_USER = "CHATTERGAME_DB_USER";
    static final String ENV_PASSWORD = "CHATTERGAME_DB_PASSWORD";

    /** Вершина стека по `D-092`: тесты и гейт работают на 16-й мажорной. */
    private static final String POSTGRES_IMAGE = "postgres:16";

    private static final String LOCAL_URL = "jdbc:postgresql://localhost:5432/chattergame";

    private static final Set<String> LIQUIBASE_TABLES =
            Set.of("databasechangelog", "databasechangeloglock");

    private static boolean resolved;
    private static String url;
    private static String user;
    private static String password;
    private static String reason;

    /** Контейнер живёт до конца JVM: закрытие в try-with-resources убило бы базу посреди тестов. */
    @SuppressWarnings("resource")
    private static PostgreSQLContainer<?> container;

    private PostgresTestSupport() {
    }

    static synchronized String url() {
        resolve();
        return requireAvailable();
    }

    static synchronized String user() {
        resolve();
        requireAvailable();
        return user;
    }

    static synchronized String password() {
        resolve();
        requireAvailable();
        return password;
    }

    private static String requireAvailable() {
        if (url == null) {
            throw new IllegalStateException(unavailableReason());
        }
        return url;
    }

    static Connection open() throws SQLException {
        return DriverManager.getConnection(url(), user(), password());
    }

    static void applyChangelog() {
        try {
            LiquibaseRunner.main(new String[] {url(), user(), password()});
        } catch (Exception e) {
            throw new IllegalStateException("Liquibase не применил changelog к " + url(), e);
        }
    }

    /** Доменные таблицы без служебных таблиц Liquibase. */
    static Set<String> tables() {
        String sql = "select tablename from pg_tables where schemaname = 'public' order by 1";
        Set<String> result = new LinkedHashSet<>();
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                String name = rows.getString(1);
                if (!LIQUIBASE_TABLES.contains(name)) {
                    result.add(name);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось прочитать список таблиц", e);
        }
        return result;
    }

    static long appliedChangeSetCount() {
        String sql = "select count(*) from databasechangelog";
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось прочитать databasechangelog", e);
        }
    }

    static String scalar(String sql) {
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("Запрос не выполнился: " + sql, e);
        }
    }

    /**
     * Доступен ли реальный Postgres. Отсутствует — тесты на БД пропускаются, а не падают:
     * разработчик без Docker и без compose-базы должен уметь собрать проект. Пропуск виден
     * в отчёте, а гейт (`scripts/gate.sh`) краснеет на любой пропущенный тест, поэтому
     * зелёный этап не может быть получен молчаливым пропуском.
     */
    static synchronized boolean isAvailable() {
        resolve();
        return url != null;
    }

    /** Причина недоступности — в сообщение assumption, чтобы пропуск был объяснён. */
    static synchronized String unavailableReason() {
        resolve();
        return reason;
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        String fromEnv = System.getenv(ENV_URL);
        if (fromEnv != null && !fromEnv.isBlank()) {
            url = fromEnv;
            user = envOrDefault(ENV_USER, "chattergame");
            password = envOrDefault(ENV_PASSWORD, "chattergame");
            if (!reachable()) {
                reason = "Не удалось подключиться к " + url + "; проверьте, что база поднята";
                url = null;
            }
            return;
        }
        // Локальная база из docker-compose — штатный путь разработки, дешевле контейнера.
        url = LOCAL_URL;
        user = envOrDefault(ENV_USER, "chattergame");
        password = envOrDefault(ENV_PASSWORD, "chattergame");
        if (reachable()) {
            return;
        }
        try {
            container = new PostgreSQLContainer<>(POSTGRES_IMAGE);
            container.start();
            url = container.getJdbcUrl();
            user = container.getUsername();
            password = container.getPassword();
        } catch (Throwable t) {
            reason = "Нет Postgres: локальная база " + LOCAL_URL + " недоступна, Docker для "
                    + POSTGRES_IMAGE + " не поднят. Выполните `docker compose --profile dev "
                    + "up -d` или задайте " + ENV_URL + ".";
            url = null;
        }
    }

    private static boolean reachable() {
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            return connection.isValid(5);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
