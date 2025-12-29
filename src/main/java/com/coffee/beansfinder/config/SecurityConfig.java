package com.coffee.beansfinder.config;

import com.coffee.beansfinder.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserService userService;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // Public endpoints - everything except /api/user/**
                .requestMatchers(
                    "/",
                    "/index.html",
                    "/chat.html",
                    "/brands.html",
                    "/products.html",
                    "/product-detail.html",
                    "/discover.html",
                    "/guide.html",
                    "/map.html",
                    "/flavor-wheel.html",
                    "/discounts.html",
                    "/my-coffees.html",
                    "/*.html",
                    "/js/**",
                    "/css/**",
                    "/cache/**",
                    "/graphee_logo.png",
                    "/favicon.ico",
                    "/api/products/**",
                    "/api/brands/**",
                    "/api/chatbot/**",
                    "/api/discover/**",
                    "/api/map/**",
                    "/api/flavor-wheel/**",
                    "/api/discounts/**",
                    "/api/analytics/**",
                    "/api/geolocation/**",
                    "/api/auth/me",
                    "/swagger-ui/**",
                    "/v3/api-docs/**"
                ).permitAll()
                // Protected endpoints
                .requestMatchers("/api/user/**").authenticated()
                // Admin endpoints
                .requestMatchers("/api/admin/**", "/api/crawler/**", "/api/graph/**").permitAll()
                .anyRequest().permitAll()
            )
            .oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(userInfo -> userInfo
                    .userService(oauth2UserService())
                )
                .defaultSuccessUrl("/", false)  // false = use saved request URL if available
                .failureUrl("/?error=auth_failed")
            )
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((request, response, authentication) -> {
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"success\": true}");
                })
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    // Return 401 for API requests, redirect for page requests
                    if (request.getRequestURI().startsWith("/api/")) {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"error\": \"Not authenticated\"}");
                    } else {
                        response.sendRedirect("/oauth2/authorization/google");
                    }
                })
            );

        return http.build();
    }

    @Bean
    public OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2UserService() {
        DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();

        return request -> {
            OAuth2User oAuth2User = delegate.loadUser(request);
            // Create or update user in our database
            userService.findOrCreateUser(oAuth2User);
            return oAuth2User;
        };
    }
}
