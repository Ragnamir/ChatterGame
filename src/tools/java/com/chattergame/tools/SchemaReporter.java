package com.chattergame.tools;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Печатает доменные таблицы БД по одной в строке. Нужен секции `migrations` гейта: список
 * сверяется с зафиксированным контрактом, и расхождение видно как обычный `diff`.
 *
 * Служебные таблицы Liquibase исключаются — они часть инструмента миграций, а не модели.
 *
 * Аргументы: url, user, password.
 */
public final class SchemaReporter {

    private static final List<String> SERVICE_TABLES =
            List.of("databasechangelog", "databasechangeloglock");

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("Ожидаются аргументы: url user password");
        }
        try (Connection connection = DriverManager.getConnection(args[0], args[1], args[2]);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "select tablename from pg_tables where schemaname = 'public' order by 1")) {
            List<String> tables = new ArrayList<>();
            while (rows.next()) {
                String name = rows.getString(1);
                if (!SERVICE_TABLES.contains(name)) {
                    tables.add(name);
                }
            }
            tables.forEach(System.out::println);
        }
    }

    private SchemaReporter() {
    }
}
