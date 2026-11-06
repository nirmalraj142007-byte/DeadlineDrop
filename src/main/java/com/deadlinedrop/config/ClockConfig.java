package com.deadlinedrop.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock systemUTC() {
        return Clock.systemUTC();
    }
}
