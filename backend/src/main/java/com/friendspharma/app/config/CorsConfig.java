package com.friendspharma.app.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        /* Trimmed, because "a, b" in a properties file yields " b" — an origin
           carrying a leading space matches nothing the browser ever sends. The
           request is refused, the browser reports only "Failed to fetch", and
           the value looks correct in every file you would think to check.
           Empty entries are dropped so a trailing comma is harmless too. */
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        /* Printed because a CORS rejection is invisible from the server side:
           the browser blocks the response after it arrives, so the access log
           shows a perfectly normal 200. Listing the accepted origins at startup
           lets you compare them against the site's address without guessing. */
        log.info("CORS: /api/** accepts browser calls from {}  [CORS_ALLOWED_ORIGINS]", origins);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsFilter(source);
    }
}
