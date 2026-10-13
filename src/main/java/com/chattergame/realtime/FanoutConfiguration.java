package com.chattergame.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Выбор реализации фан-аута (`D-084`). По умолчанию — `InJvmFanout`: один инстанс,
 * доставка в память. Несколько инстансов включаются свойством
 * `chattergame.realtime.fanout=postgres`, и тогда переключение остаётся конфигурацией,
 * а не правкой ядра.
 */
@Configuration
public class FanoutConfiguration {

    /** Второй инстанс: уведомление через `LISTEN/NOTIFY`, тело читается из таблицы. */
    @Bean
    @ConditionalOnProperty(prefix = "chattergame.realtime", name = "fanout", havingValue = "postgres")
    public Fanout postgresNotifyFanout(DataSource dataSource, JdbcTemplate jdbc) {
        return new PostgresNotifyFanout(dataSource, jdbc);
    }

    @Bean
    @ConditionalOnMissingBean(Fanout.class)
    public Fanout inJvmFanout() {
        return new InJvmFanout();
    }
}
