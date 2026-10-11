package com.chattergame.util;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv7: 48 бит времени в миллисекундах, затем версия и случайность. Внешние PK выдаёт
 * приложение, а не БД: функция {@code uuidv7()} появилась только в PostgreSQL 18,
 * стек зафиксирован на 16.
 *
 * <p>Временная часть сохраняет порядок вставки, поэтому новые строки ложатся в конец
 * B-дерева и индекс не фрагментируется на каждом INSERT, как со случайным UUIDv4.
 */
public final class Uuids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuids() {
    }

    public static UUID v7() {
        long millis = System.currentTimeMillis();

        byte[] random = new byte[10];
        RANDOM.nextBytes(random);

        long randA = ((random[0] & 0x0FL) << 8) | (random[1] & 0xFFL);
        long most = ((millis & 0x0000_FFFF_FFFF_FFFFL) << 16) | 0x7000L | randA;

        long tail = 0;
        for (int i = 2; i < random.length; i++) {
            tail = (tail << 8) | (random[i] & 0xFFL);
        }
        long least = 0x8000_0000_0000_0000L | (tail & 0x3FFF_FFFF_FFFF_FFFFL);

        return new UUID(most, least);
    }
}
