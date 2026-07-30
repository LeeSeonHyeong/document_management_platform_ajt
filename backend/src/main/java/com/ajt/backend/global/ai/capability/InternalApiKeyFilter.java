package com.ajt.backend.global.ai.capability;

import com.ajt.backend.global.ai.config.AiApiProperties;
import com.ajt.backend.global.error.ErrorCode;
import com.ajt.backend.global.error.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class InternalApiKeyFilter extends OncePerRequestFilter {
    private final AiApiProperties properties;
    private final SecurityErrorResponseWriter responseWriter;
    public InternalApiKeyFilter(AiApiProperties properties, SecurityErrorResponseWriter responseWriter) {
        this.properties = properties;
        this.responseWriter = responseWriter;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.internalApiKey().equals(request.getHeader("X-Internal-API-Key"))) {
            responseWriter.write(response, ErrorCode.UNAUTHORIZED, request.getRequestURI());
            return;
        }
        chain.doFilter(request, response);
    }
}
