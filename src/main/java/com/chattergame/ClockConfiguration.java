package com.chattergame;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Часы — бин, а не `Instant.now()` по месту. Скользящая сессия, TTL токена сброса и
 * проверка «ссылка ещё жива» зависят от текущего времени; без подменяемых часов эти
 * правила проверяются только сном в тесте.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
