package com.ajt.backend.global.config;

import static org.springframework.security.config.Customizer.withDefaults;

import com.ajt.backend.global.auth.AccessTokenAuthenticationFilter;
import com.ajt.backend.global.auth.CsrfProtectionFilter;
import com.ajt.backend.global.ai.capability.InternalApiKeyFilter;
import com.ajt.backend.global.error.RestAccessDeniedHandler;
import com.ajt.backend.global.error.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AccessTokenAuthenticationFilter accessTokenAuthenticationFilter,
            CsrfProtectionFilter csrfProtectionFilter,
            InternalApiKeyFilter internalApiKeyFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers.frameOptions(frameOptions -> frameOptions.sameOrigin()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/health",
                                "/actuator/health",
                                "/h2-console/**",
                                "/api/v1/auth/login",
                                "/api/v1/auth/csrf",
                                "/api/v1/auth/signup",
                                "/api/v1/auth/password-reset-requests",
                                "/api/v1/auth/password-reset-verify",
                                "/api/v1/auth/password-resets",
                                "/api/v1/signup-departments"
                        ).permitAll()
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .addFilterBefore(accessTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(internalApiKeyFilter, AccessTokenAuthenticationFilter.class)
                .addFilterAfter(csrfProtectionFilter, AccessTokenAuthenticationFilter.class)
                .httpBasic(withDefaults())
                .build();
    }
}
