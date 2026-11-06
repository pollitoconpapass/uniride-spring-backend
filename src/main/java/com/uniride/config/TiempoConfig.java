package com.uniride.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TiempoConfig {
    public static final ZoneId LIMA = ZoneId.of("America/Lima");

    @Bean
    public Clock clock() {
        return Clock.system(LIMA);
    }

    @Bean(name = "taskScheduler")
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2); scheduler.setThreadNamePrefix("viajes-"); return scheduler;
    }

    @Bean(name = "correoScheduler")
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler correoScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("correo-"); return scheduler;
    }
}
