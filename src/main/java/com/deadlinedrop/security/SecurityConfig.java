package com.deadlinedrop.security;

import com.deadlinedrop.api.ErrorCodes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

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
    private final boolean cloudProfile;

    /** No script, style or frame comes from anywhere but this origin; only the bucket and the local storage server take uploads. */
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data:; connect-src 'self' https://storage.googleapis.com http://localhost:9000; "
            + "frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

    static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=()";

    public SecurityConfig(ProblemDetailWriter problemDetailWriter, JwtService jwtService, Environment environment) {
        this.problemDetailWriter = problemDetailWriter;
        this.jwtService = jwtService;
        this.cloudProfile = environment.acceptsProfiles(Profiles.of("cloud"));
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
            // The app allows no cross-origin calls: pages and API share one origin, so no CORS
            // source is configured and no Access-Control-Allow-Origin header is ever sent.
            // X-Content-Type-Options: nosniff is on by default and stays on.
            .headers(headers -> {
                headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY));
                headers.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER));
                headers.permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY));
                if (cloudProfile) {
                    // Cloud Run ends TLS at its proxy, so the app sees plain HTTP; HSTS is sent on every response.
                    headers.httpStrictTransportSecurity(hsts -> hsts
                            .maxAgeInSeconds(31_536_000)
                            .requestMatcher(AnyRequestMatcher.INSTANCE));
                } else {
                    headers.httpStrictTransportSecurity(hsts -> hsts.disable());
                }
            })
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
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/consent-v1.html", "/css/**", "/js/**", "/student/**", "/teacher/**", "/admin/**.html", "/favicon.ico").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/time").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/config").permitAll()
                .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                .requestMatchers("/actuator/health/**").permitAll()
                .anyRequest().authenticated());

        return http.build();
    }
}
