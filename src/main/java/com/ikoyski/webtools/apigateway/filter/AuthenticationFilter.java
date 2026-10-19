package com.ikoyski.webtools.apigateway.filter;
 
import java.nio.charset.StandardCharsets;
 
import javax.crypto.SecretKey;
 
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
 
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import reactor.core.publisher.Mono;
 
@Component
public class AuthenticationFilter extends AbstractGatewayFilterFactory<AuthenticationFilter.Config> {
 
    private static final Logger log = LoggerFactory.getLogger(AuthenticationFilter.class);
 
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String USER_ID_CLAIM = "uid";
 
    // jjwt parsers are thread-safe, so build it once.
    private final JwtParser parser;
 
    public static class Config {}
 
    public AuthenticationFilter(@Value("${security.jwt.token.secret-key}") String secret) {
        super(Config.class);
        SecretKey secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.parser = Jwts.parserBuilder().setSigningKey(secretKey).build();
    }
 
    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
 
            // 1. Look for a Bearer Authorization header
            String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
                return unauthorized(exchange);
            }
 
            // 2. Strip "Bearer " prefix to extract the raw token string
            String token = authHeader.substring(BEARER_PREFIX.length());
 
            try {
                // 3. Parse and validate: checks the signature and expiry
                Claims claims = parser.parseClaimsJws(token).getBody();
 
                // 4. Extract identity
                String userEmail = claims.getSubject();
                String userId = extractUserId(claims);
 
                if (userEmail == null || userId == null || userId.isBlank()) {
                    log.debug("JWT rejected: missing subject or uid claim");
                    return unauthorized(exchange);
                }
 
                // 5. Forward identity downstream. header() REPLACES any value the client sent,
                // so these cannot be spoofed on routes that use this filter.
                ServerHttpRequest modifiedRequest = request.mutate()
                        .header("X-User-Email", userEmail)
                        .header("X-User-Id", userId)
                        .build();
 
                return chain.filter(exchange.mutate().request(modifiedRequest).build());
 
            } catch (Exception e) {
                // Signature tampered with, token expired, or malformed. Never log the token itself.
                log.debug("JWT rejected: {}", e.getMessage());
                return unauthorized(exchange);
            }
        };
    }
 
    /**
     * The user id comes only from the dedicated "uid" claim. "jti" is a token id
     * so tokens without "uid" are rejected.
     */
    private static String extractUserId(Claims claims) {
        Object uid = claims.get(USER_ID_CLAIM);
        return uid == null ? null : uid.toString();
    }
 
    private static Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return exchange.getResponse().setComplete();
    }
}