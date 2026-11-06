package com.deadlinedrop.security;

import com.deadlinedrop.api.ErrorCodes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Base security configuration.
 *
 * <p>Configures stateless HS256 JWT bearer token validation, ProblemDetail error handling,
 * and method security.
 */
@Configuration
@Profile("!storage")
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final ProblemDetailWriter problemDetailWriter;
    private final JwtService jwtService;

    public SecurityConfig(ProblemDetailWriter problemDetailWriter, JwtService jwtService) {
        this.problemDetailWriter = problemDetailWriter;
        this.jwtService = jwtService;
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(jwtService.getSecretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthorityPrefix("ROLE_");
        authoritiesConverter.setAuthoritiesClaimName("role");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
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
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder())
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
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
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers("/actuator/health/**").permitAll()
                .anyRequest().authenticated());

        return http.build();
    }
}
