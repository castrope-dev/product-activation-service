package com.product.activation.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.product.activation.api.error.ApiProblemFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    JwtDecoder jwtDecoder(@Value("${activation.security.issuer}") String issuer,
                          @Value("${activation.security.jwk-set-uri}") String jwks,
                          @Value("${activation.security.audience}") String audience) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            boolean valid = jwt.getAudience() != null && jwt.getAudience().contains(audience) && jwt.getExpiresAt() != null
                    && jwt.getSubject() != null && !jwt.getSubject().isBlank() && jwt.getSubject().length() <= 200;
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Required claims are invalid", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), claims));
        return decoder;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/prometheus").hasAuthority("SCOPE_metrics.read")
                        .requestMatchers(HttpMethod.POST, "/api/v1/requests").hasAuthority("SCOPE_requests.write")
                        .requestMatchers(HttpMethod.GET, "/api/v1/requests", "/api/v1/requests/**").hasAuthority("SCOPE_requests.read")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {
                        })
                        .authenticationEntryPoint((request, response, error) -> {
                            response.setStatus(401);
                            response.setHeader("WWW-Authenticate", "Bearer");
                            response.setContentType("application/problem+json");
                            mapper.writeValue(response.getOutputStream(), ApiProblemFactory.problem(401, "Autenticação necessária", request));
                        }))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> {
                            response.setStatus(401);
                            response.setHeader("WWW-Authenticate", "Bearer");
                            response.setContentType("application/problem+json");
                            mapper.writeValue(response.getOutputStream(), ApiProblemFactory.problem(401, "Autenticação necessária", request));
                        })
                        .accessDeniedHandler((request, response, error) -> {
                            response.setStatus(403);
                            response.setContentType("application/problem+json");
                            mapper.writeValue(response.getOutputStream(), ApiProblemFactory.problem(403, "Escopo insuficiente", request));
                        }))
                .build();
    }
}
