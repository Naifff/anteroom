package org.anteroom.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /**
     * Часы отдельным бином, чтобы сроки жизни проверялись тестом, а не {@code sleep}'ом.
     * UTC — потому что в базе лежит абсолютный дедлайн в миллисекундах UTC.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
