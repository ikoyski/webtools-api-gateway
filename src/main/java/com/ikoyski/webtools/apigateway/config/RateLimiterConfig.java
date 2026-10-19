package com.ikoyski.webtools.apigateway.config;

import java.net.InetSocketAddress;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterConfig {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String CF_IP_HEADER = "CF-Connecting-IP";

    /**
     * Bucket per client IP. Use ONLY on public routes (login, signup, forgot/reset password).
     *
     * CF-Connecting-IP is set by Cloudflare. It is trustworthy only if the gateway is reachable
     * solely through the tunnel; anyone who can reach the gateway directly (e.g. from the LAN)
     * can forge it. Keep your existing ipKeyResolver logic here if it differs from this.
     */
    @Bean
    @Primary
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            ServerHttpRequest request = exchange.getRequest();

            String ip = request.getHeaders().getFirst(CF_IP_HEADER);
            if (ip == null || ip.isBlank()) {
                InetSocketAddress remote = request.getRemoteAddress();
                ip = (remote == null || remote.getAddress() == null)
                        ? "unknown"
                        : remote.getAddress().getHostAddress();
            }
            return Mono.just("ip:" + ip.trim());
        };
    }

    /**
     * Bucket per user. Use ONLY on routes where AuthenticationFilter runs BEFORE
     * RequestRateLimiter in the filter list.
     *
     * AuthenticationFilter has already validated the JWT (signature and expiry) and replaced
     * X-User-Id with the value of the token's jti claim, so there is no need to parse the token
     * again here. On a route without AuthenticationFilter this header would be client-controlled
     * and spoofable, so never attach this resolver to a public route.
     *
     * An empty key makes the rate limiter reject the request (403 by default). That should not
     * happen on the protected route, because AuthenticationFilter already returns 401 for
     * missing or invalid tokens.
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst(USER_ID_HEADER);
            if (userId == null || userId.isBlank()) {
                return Mono.empty();
            }
            return Mono.just("user:" + userId.trim());
        };
    }

    @Bean
    public KeyResolver mcpUserKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            return (userId == null || userId.isBlank())
                    ? Mono.empty()
                    : Mono.just("mcp:user:" + userId.trim());
        };
    }

}