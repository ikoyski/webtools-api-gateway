package com.ikoyski.webtools.apigateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

import reactor.core.publisher.Mono;

/**
 * Validates "Authorization: Bearer &lt;access token&gt;" against the auth server and forwards the identity
 * downstream as trusted X-User-* headers. Values are replaced, so a client cannot supply its own.
 *
 * Routes should configure an exact audience plus read/write scopes. When configured, this filter rejects
 * tokens not issued for that resource and requires the read scope for safe methods and the write scope
 * for mutating methods. The uid claim is the central identity UUID (also the token subject).
 * The factory name used in route definitions is the class name: "- AuthenticationFilter".
 *
 * Optional route argument {@code resourceMetadata}: the public URL of this resource's OAuth protected-resource
 * metadata (RFC 9728). When set, the 401 challenge carries it, which is how an MCP client such as the Claude
 * connector discovers which authorization server to use:
 *   WWW-Authenticate: Bearer resource_metadata="https://.../.well-known/oauth-protected-resource/..."
 * A token that was sent but rejected also gets error="invalid_token" so clients know to refresh or sign in again.
 */
@Component
public class AuthenticationFilter extends AbstractGatewayFilterFactory<AuthenticationFilter.Config> {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String USER_ID_CLAIM = "uid";
    private static final String EMAIL_CLAIM = "email";

    private final ReactiveJwtDecoder jwtDecoder;

    public static class Config {
        private String resourceMetadata;
        private String audience;
        private String readScope;
        private String writeScope;

        public String getResourceMetadata() { return resourceMetadata; }
        public void setResourceMetadata(String resourceMetadata) { this.resourceMetadata = resourceMetadata; }
        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = audience; }
        public String getReadScope() { return readScope; }
        public void setReadScope(String readScope) { this.readScope = readScope; }
        public String getWriteScope() { return writeScope; }
        public void setWriteScope(String writeScope) { this.writeScope = writeScope; }
    }

    public AuthenticationFilter(ReactiveJwtDecoder jwtDecoder) {
        super(Config.class);
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("resourceMetadata");
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
                return unauthorized(exchange, config, false);
            }
            String token = authHeader.substring(BEARER_PREFIX.length());

            return Mono.defer(() -> jwtDecoder.decode(token))
                    .flatMap(jwt -> forward(exchange, chain, config, jwt))
                    .onErrorResume(JwtException.class, e -> {
                        // Bad signature, expired, wrong issuer, malformed, or the key set could not be loaded.
                        // Never log the token itself.
                        log.debug("JWT rejected: {}", e.getMessage());
                        return unauthorized(exchange, config, true);
                    });
        };
    }

    private Mono<Void> forward(ServerWebExchange exchange, GatewayFilterChain chain, Config config, Jwt jwt) {
        String userId = jwt.getClaimAsString(USER_ID_CLAIM);
        String email = jwt.getClaimAsString(EMAIL_CLAIM);
        if (isBlank(userId) || isBlank(email)) {
            log.debug("JWT rejected: missing uid or email claim");
            return unauthorized(exchange, config, true);
        }

        if (config != null && !isBlank(config.getAudience())
                && (jwt.getAudience() == null || !jwt.getAudience().contains(config.getAudience().trim()))) {
            log.debug("JWT rejected: token audience does not match this resource");
            return unauthorized(exchange, config, true);
        }

        String requiredScope = requiredScope(exchange, config);
        if (!isBlank(requiredScope)) {
            List<String> scopes = jwt.getClaimAsStringList("scope");
            if (scopes == null || !scopes.contains(requiredScope.trim())) {
                return insufficientScope(exchange, config, requiredScope.trim());
            }
        }

        String displayName = jwt.getClaimAsString("name");
        String avatarUrl = jwt.getClaimAsString("picture");
        List<String> scopes = jwt.getClaimAsStringList("scope");
        ServerHttpRequest modified = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set("X-User-Id", userId);
                    headers.set("X-User-Email", email);
                    if (!isBlank(displayName)) headers.set("X-User-Display-Name", displayName);
                    else headers.remove("X-User-Display-Name");
                    if (!isBlank(avatarUrl)) headers.set("X-User-Avatar-Url", avatarUrl);
                    else headers.remove("X-User-Avatar-Url");
                    if (scopes != null) headers.set("X-User-Scopes", String.join(" ", scopes));
                    else headers.remove("X-User-Scopes");
                })
                .build();
        return chain.filter(exchange.mutate().request(modified).build());
    }

    private static String requiredScope(ServerWebExchange exchange, Config config) {
        if (config == null) return null;
        String method = exchange.getRequest().getMethod() == null
                ? "" : exchange.getRequest().getMethod().name();
        boolean safeMethod = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
        return safeMethod ? config.getReadScope() : config.getWriteScope();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Mono<Void> insufficientScope(ServerWebExchange exchange, Config config, String requiredScope) {
        StringBuilder challenge = new StringBuilder("Bearer error=\"insufficient_scope\", scope=\"")
                .append(requiredScope).append('"');
        String metadata = config == null ? null : config.getResourceMetadata();
        if (!isBlank(metadata)) {
            challenge.append(", resource_metadata=\"").append(metadata.trim()).append('"');
        }
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, challenge.toString());
        return exchange.getResponse().setComplete();
    }

    private static Mono<Void> unauthorized(ServerWebExchange exchange, Config config, boolean tokenWasRejected) {
        StringBuilder challenge = new StringBuilder("Bearer");
        String separator = " ";
        if (tokenWasRejected) {
            challenge.append(separator).append("error=\"invalid_token\"");
            separator = ", ";
        }
        String metadata = config == null ? null : config.getResourceMetadata();
        if (metadata != null && !metadata.isBlank()) {
            challenge.append(separator).append("resource_metadata=\"").append(metadata.trim()).append('"');
        }
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, challenge.toString());
        return exchange.getResponse().setComplete();
    }
}
