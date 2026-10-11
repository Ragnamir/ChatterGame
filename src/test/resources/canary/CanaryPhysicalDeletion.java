package com.chattergame.skeleton.canary;

/**
 * Канарейка для проверки детектора физического удаления (`D-010`).
 *
 * Намеренно содержит запрещённую конструкцию и не лежит в продовых исходниках: детектор,
 * который не находит нарушение здесь, не найдёт его и в реальном коде. Файл не входит
 * в компиляцию main — лежит в ресурсах тестов.
 */
class CanaryPhysicalDeletion {

    void purgeOldMessages(long chatId) {
        String sql = "DELETE FROM message WHERE chat_id = ?";
        run(sql, chatId);
    }

    void run(String sql, long chatId) {
        throw new UnsupportedOperationException("канарейка: " + sql + chatId);
    }
}
