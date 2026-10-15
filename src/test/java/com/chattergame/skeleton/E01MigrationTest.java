package com.chattergame.skeleton;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Миграции: применяются на чистой базе за один проход, повторный запуск ничего не меняет,
 * итоговый список таблиц совпадает с зафиксированным контрактом (`E01`).
 *
 * Гейт пересоздаёт базу до тестов, поэтому применение changelog в {@code @BeforeAll} и есть
 * прогон с нуля. Явная подготовка нужна потому, что порядок тестовых методов не определён:
 * без неё проверки схемы зависели бы от того, какой метод выполнился первым.
 */
class E01MigrationTest {

    @BeforeAll
    static void migrate() {
        Assumptions.assumeTrue(PostgresTestSupport.isAvailable(),
                PostgresTestSupport.unavailableReason());
        PostgresTestSupport.applyChangelog();
    }

    @Test
    void migrateFromScratchAppliesAllChanges() {
        assertThat(PostgresTestSupport.appliedChangeSetCount())
                .as("changeset-ов должно примениться ровно столько, сколько в changelog")
                .isEqualTo(48);
    }

    @Test
    void secondRunIsNoop() {
        PostgresTestSupport.applyChangelog();
        long afterFirst = PostgresTestSupport.appliedChangeSetCount();

        PostgresTestSupport.applyChangelog();

        assertThat(PostgresTestSupport.appliedChangeSetCount())
                .as("повторный запуск Liquibase не должен применять ни одного changeset")
                .isEqualTo(afterFirst);
    }

    @Test
    void schemaMatchesFrozenTableList() {
        Set<String> expected = ContractFiles.expectedTables();
        Set<String> actual = PostgresTestSupport.tables();

        assertThat(actual)
                .as("список таблиц должен совпадать с зафиксированным контрактом")
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void inboxResumeIndexExists() {
        String indexDef = PostgresTestSupport.scalar(
                "select indexdef from pg_indexes where indexname = 'ix_inbox_entry_subject_chat_seq'");

        assertThat(indexDef)
                .as("индекс resume и бейджей непрочитанного обязателен (D-083, D-086)")
                .isNotNull();
        assertThat(indexDef).contains("subject_kind").contains("subject_id").contains("event_seq");
    }

    @Test
    void journalAndExternalIdsUseExpectedTypes() {
        assertThat(PostgresTestSupport.scalar(
                "select data_type from information_schema.columns "
                        + "where table_name = 'game_event' and column_name = 'seq'"))
                .isEqualTo("bigint");
        assertThat(PostgresTestSupport.scalar(
                "select data_type from information_schema.columns "
                        + "where table_name = 'game' and column_name = 'id'"))
                .isEqualTo("uuid");
        assertThat(PostgresTestSupport.scalar(
                "select count(*) from pg_indexes where indexname = 'ux_game_event_game_seq'"))
                .as("номер события уникален в пределах игры")
                .isEqualTo("1");
    }
}
