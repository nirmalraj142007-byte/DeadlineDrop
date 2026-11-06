package com.deadlinedrop.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Security configuration for the dedicated storage server endpoint (/storage/**).
 *
 * <p>Requests are permitted without Bearer authentication because the HMAC query signature
 * acts as the credential. CORS is enabled to permit direct browser uploads across app origins.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "dd.storage.server.enabled", havingValue = "true")
public class StorageServerSecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain storageSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/storage/**")
            .cors(cors -> cors.configurationSource(storageCorsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public CorsConfigurationSource storageCorsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
                "http://localhost:8080",
                "http://localhost:8081",
                "http://localhost:8082",
                "http://localhost:8083"
        ));
        config.setAllowedMethods(List.of("PUT", "GET", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type"));
        config.setMaxAge(600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/storage/**", config);
        return source;
    }
}
