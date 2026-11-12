package com.deadlinedrop.security;

import com.deadlinedrop.api.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
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
 *   <li>every other profile (the cloud): a Google-signed OIDC token from Cloud Scheduler grants
 *       ROLE_INTERNAL when {@code INTERNAL_AUDIENCE} and {@code SCHEDULER_SA_EMAIL} are set (arm C);
 *       with neither set (arms A and B, which have no scheduler) the chain denies all.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@Profile("!storage")
public class InternalSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(InternalSecurityConfig.class);

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
    @Profile("!local & !test")
    @ConfigurationProperties(prefix = "dd.internal.oidc")
    @Validated
    public InternalOidcProperties internalOidcProperties() {
        return new InternalOidcProperties();
    }

    @Bean
    @Order(1)
    @Profile("!local & !test")
    public SecurityFilterChain internalCloudFilterChain(HttpSecurity http, InternalOidcProperties properties)
            throws Exception {
        if (!properties.isConfigured()) {
            log.atInfo().addKeyValue("event", "internal_auth_mode").addKeyValue("mode", "deny_all")
                    .log("INTERNAL_AUDIENCE and SCHEDULER_SA_EMAIL are not set; /internal/** denies every request");
            base(http).authorizeHttpRequests(auth -> auth.anyRequest().denyAll());
            return http.build();
        }
        log.atInfo().addKeyValue("event", "internal_auth_mode").addKeyValue("mode", "google_oidc")
                .addKeyValue("audience", properties.getAudience())
                .addKeyValue("schedulerEmail", properties.getSchedulerEmail())
                .log("/internal/** accepts Google-signed OIDC tokens from the scheduler's service account");
        JwtAuthenticationConverter toInternal = new JwtAuthenticationConverter();
        // Every check is in the validator; a token that passes it is the scheduler and nothing else.
        toInternal.setJwtGrantedAuthoritiesConverter(jwt -> AuthorityUtils.createAuthorityList("ROLE_INTERNAL"));
        base(http)
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(GoogleOidcTokens.decoder(properties))
                                .jwtAuthenticationConverter(toInternal))
                        .authenticationEntryPoint((request, response, e) -> problemDetailWriter.write(
                                response, request, HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHENTICATED,
                                "Internal endpoints need the scheduler's OIDC token")))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("INTERNAL"));
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
