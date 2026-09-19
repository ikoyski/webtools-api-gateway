package com.ikoyski.webtools.apigateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class AuthenticationFilter extends AbstractGatewayFilterFactory<AuthenticationFilter.Config> {

    private final SecretKey secretKey;

    public static class Config {}

    public AuthenticationFilter(@Value("${security.jwt.token.secret-key}") String secret) {
        super(Config.class);
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();

            // 1. Look for Authorization Header
            if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            // 2. Strip "Bearer " prefix to extract raw token string
            String token = authHeader.substring(7);

            try {
                // 3. PARSE AND VALIDATE THE TOKEN
                // This checks the cryptographic signature and automatically checks if token is expired
                Claims claims = Jwts.parserBuilder()
                        .setSigningKey(secretKey) // Re-verify signature using the secret
                        .build()
                        .parseClaimsJws(token)
                        .getBody();

                // 4. EXTRACT DATA MATCHING YOUR BACKEND STRUCTURE
                String userEmail = claims.getSubject(); // Reads user.getEmail() from backend
                String userId = claims.getId();         // Reads user.getId() from backend

                // 5. FORWARD DATA DOWNSTREAM TO KANBAN SERVICES
                // Mutate the request headers so Board/Column/Card controllers don't have to parse JWTs
                ServerHttpRequest modifiedRequest = request.mutate()
                        .header("X-User-Email", userEmail)
                        .header("X-User-Id", userId)
                        .build();

                return chain.filter(exchange.mutate().request(modifiedRequest).build());

            } catch (Exception e) {
                // Triggers if signature is tampered with, token expired, or malformed
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
        };
    }
}