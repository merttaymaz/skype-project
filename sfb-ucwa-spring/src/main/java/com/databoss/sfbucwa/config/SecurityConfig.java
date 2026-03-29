package com.databoss.sfbucwa.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Swagger & Actuator açık
                .requestMatchers(
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/actuator/**",
                    "/actuator/health/**",
                    "/api/v1/recordings/**"
                ).permitAll()
                // UCWA callback endpoint
                .requestMatchers("/api/v1/ucwa/callback").permitAll()
                // Diğer tüm endpointler korumalı
                .anyRequest().authenticated()
            )
            .oauth2Client(oauth2 -> {})
            .httpBasic(basic -> {}); // Test amaçlı — prod'da kaldırılmalı

        return http.build();
    }
}
