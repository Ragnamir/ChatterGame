package com.chattergame.tools;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Применяет changelog к указанной БД. Нужен гейту и разработчику: проверяет миграции
 * на чистой базе без поднятия приложения и без Liquibase CLI, который тянет артефакты
 * вне Maven Central.
 *
 * Аргументы: url, user, password.
 */
public final class LiquibaseRunner {

    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("Ожидаются аргументы: url user password");
        }
        try (Connection connection = DriverManager.getConnection(args[0], args[1], args[2])) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private LiquibaseRunner() {
    }
}
