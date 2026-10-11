package com.chattergame.tools;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Пересоздаёт указанную БД. Нужен гейту: тесты миграций осмысленны только на чистой базе,
 * и требовать от разработчика ручного `dropdb` нельзя — проверка перестанет запускаться.
 *
 * Аргументы: url, user, password. Подключение идёт к служебной базе `postgres` того же
 * сервера, сама цель удаляется вместе с активными соединениями.
 */
public final class DatabaseResetter {

    private static final Pattern URL =
            Pattern.compile("jdbc:postgresql://([^:/?]+)(?::(\\d+))?/([^?/]+)");
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("Ожидаются аргументы: url user password");
        }
        Matcher matcher = URL.matcher(args[0]);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Не разобрать JDBC-адрес: " + args[0]);
        }
        String host = matcher.group(1);
        String port = matcher.group(2) == null ? "5432" : matcher.group(2);
        String database = matcher.group(3);
        if (!SAFE_NAME.matcher(database).matches()) {
            throw new IllegalArgumentException("Небезопасное имя базы: " + database);
        }

        String adminUrl = "jdbc:postgresql://" + host + ":" + port + "/postgres";
        try (Connection connection = DriverManager.getConnection(adminUrl, args[1], args[2]);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS \"" + database + "\" WITH (FORCE)");
            statement.execute("CREATE DATABASE \"" + database + "\"");
        }
    }

    private DatabaseResetter() {
    }
}
