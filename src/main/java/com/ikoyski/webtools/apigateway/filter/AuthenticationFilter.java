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
 * downstream as X-User-Id and X-User-Email. Header values are REPLACED, so a client cannot supply its own.
 *
 * The token must be an RS256 JWT from the configured issuer (see JwtDecoderConfig) carrying the claims
 * "uid" (the Kanban user id) and "email". Any failure returns an empty-body 401.
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

        public String getResourceMetadata() {
            return resourceMetadata;
        }

        public void setResourceMetadata(String resourceMetadata) {
            this.resourceMetadata = resourceMetadata;
        }
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
        ServerHttpRequest modified = exchange.getRequest().mutate()
                .header("X-User-Id", userId)
                .header("X-User-Email", email)
                .build();
        return chain.filter(exchange.mutate().request(modified).build());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
