package com.deadlinedrop.security;

import com.deadlinedrop.api.ErrorCodes;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.validation.annotation.Validated;

import java.util.Objects;

/**
 * Security for {@code /internal/**}, checked before the JWT chain. A student or teacher JWT never
 * reaches these endpoints: this chain ignores bearer tokens entirely.
 *
 * <ul>
 *   <li>local and test: the {@code X-Internal-Token} header grants ROLE_INTERNAL.</li>
 *   <li>every other profile (the cloud): deny all until Google OIDC from Cloud Scheduler is added.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@Profile("!storage")
public class InternalSecurityConfig {

    private final ProblemDetailWriter problemDetailWriter;

    public InternalSecurityConfig(ProblemDetailWriter problemDetailWriter) {
        this.problemDetailWriter = Objects.requireNonNull(problemDetailWriter, "problemDetailWriter must not be null");
    }

    @Bean
    @Profile({"local", "test"})
    @ConfigurationProperties(prefix = "dd.internal")
    @Validated
    public InternalTokenProperties internalTokenProperties() {
        return new InternalTokenProperties();
    }

    @Bean
    @Order(1)
    @Profile({"local", "test"})
    public SecurityFilterChain internalTokenFilterChain(HttpSecurity http, InternalTokenProperties properties)
            throws Exception {
        base(http)
                .addFilterBefore(new InternalTokenFilter(properties.getToken()), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("INTERNAL"));
        return http.build();
    }

    @Bean
    @Order(1)
    @Profile("!local & !test")
    public SecurityFilterChain internalDenyAllFilterChain(HttpSecurity http) throws Exception {
        base(http).authorizeHttpRequests(auth -> auth.anyRequest().denyAll());
        return http.build();
    }

    private HttpSecurity base(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/internal/**")
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // No cookies or sessions on this chain; the credential is a header a browser never sends.
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) -> problemDetailWriter.write(
                                response, request, HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHENTICATED,
                                "Internal endpoints need the scheduler's credential"))
                        .accessDeniedHandler((request, response, e) -> problemDetailWriter.write(
                                response, request, HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN,
                                "Internal endpoints are for the scheduler only")));
    }
}
