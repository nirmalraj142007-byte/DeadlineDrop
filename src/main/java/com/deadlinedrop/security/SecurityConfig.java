package com.deadlinedrop.security;

import com.deadlinedrop.api.ErrorCodes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Base security configuration.
 *
 * <p>Phase 0: stateless session, /actuator/health/** public, everything else requires
 * authentication. Phase 1 adds RFC 9457 ProblemDetail error bodies; phase 5 adds JWT
 * decoder and bearer-token filter.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ProblemDetailWriter problemDetailWriter;

    public SecurityConfig(ProblemDetailWriter problemDetailWriter) {
        this.problemDetailWriter = problemDetailWriter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .sessionManagement(sm ->
                sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // CSRF protection is disabled because DeadlineDrop uses stateless bearer tokens
            // in an Authorization header and no session cookies, so cross-site requests
            // cannot implicitly attach credentials.
            .csrf(csrf -> csrf.disable())
            .httpBasic(httpBasic -> httpBasic.disable())
            .formLogin(formLogin -> formLogin.disable())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) ->
                    problemDetailWriter.write(
                        response,
                        request,
                        HttpStatus.UNAUTHORIZED,
                        ErrorCodes.UNAUTHENTICATED,
                        authException.getMessage() != null
                            ? authException.getMessage()
                            : "Full authentication is required to access this resource"
                    )
                )
                .accessDeniedHandler((request, response, accessDeniedException) ->
                    problemDetailWriter.write(
                        response,
                        request,
                        HttpStatus.FORBIDDEN,
                        ErrorCodes.FORBIDDEN,
                        accessDeniedException.getMessage() != null
                            ? accessDeniedException.getMessage()
                            : "Access is denied"
                    )
                )
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**").permitAll()
                .anyRequest().authenticated());

        return http.build();
    }
}
