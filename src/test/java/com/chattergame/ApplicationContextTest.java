package com.chattergame;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Контекст поднимается: конфигурация профиля `dev` согласована, обязательных внешних
 * систем (SMTP, ключи LLM) для старта нет.
 */
@SpringBootTest
class ApplicationContextTest {

    @Test
    void contextLoads() {
    }
}
