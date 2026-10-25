package com.ielop.platform.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Central Spring Security configuration.
 *
 * <p>Design (kept deliberately simple, by design):</p>
 * <ul>
 *   <li>Stateless — no HTTP session, no CSRF token (there is no
 *       cookie-based session to protect; the API is consumed with a
 *       bearer token, which is not automatically sent by the browser the
 *       way a cookie is).</li>
 *   <li>{@code /api/health}, {@code /actuator/health}, {@code /actuator/info} and
 *       {@code /api/auth/login} are public. Every other actuator endpoint is
 *       ADMINISTRATOR only (SEC-005). Everything else requires a valid JWT.</li>
 *   <li>Role checks beyond "authenticated" are enforced with
 *       {@code @PreAuthorize} at the controller/service boundary (method
 *       security), not with a long list of {@code antMatchers} here — see
 *       {@code UserController}.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final List<String> corsAllowedOrigins;
    private final boolean apiDocsEnabled;

    public SecurityConfig(
            JwtService jwtService,
            ObjectMapper objectMapper,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            @Value("${security.cors.allowed-origins}") String corsAllowedOrigins,
            @Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled
    ) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.corsAllowedOrigins = List.of(corsAllowedOrigins.split(","));
        this.apiDocsEnabled = apiDocsEnabled;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtService, objectMapper);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsAllowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Request-ID", "X-Correlation-ID"));
        configuration.setExposedHeaders(List.of("X-Request-ID"));
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(content -> {})
                        .xssProtection(xss -> {}))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> {
                    if (apiDocsEnabled) {
                        // Dev profile only: the OpenAPI document and its UI (section 8.7).
                        auth.requestMatchers("/api/docs", "/api/docs/**", "/swagger-ui/**").permitAll();
                    }
                    auth
                        .requestMatchers("/api/health", "/actuator/health", "/actuator/info", "/api/auth/login").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMINISTRATOR")
                        .requestMatchers("/api/admin/**", "/api/audit/**", "/api/audit").hasRole("ADMINISTRATOR")
                        .anyRequest().authenticated();
                })
                .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
