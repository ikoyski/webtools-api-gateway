package com.ikoyski.webtools.apigateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

class AuthenticationFilterTest {

    private final ReactiveJwtDecoder decoder = mock(ReactiveJwtDecoder.class);
    private final GatewayFilter filter = new AuthenticationFilter(decoder).apply(new AuthenticationFilter.Config());
    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    private final GatewayFilterChain chain = exchange -> {
        forwarded.set(exchange);
        return Mono.empty();
    };

    private static Jwt jwt(String uid, String email) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").subject(uid == null ? "x" : uid);
        if (uid != null) {
            builder.claim("uid", uid);
        }
        if (email != null) {
            builder.claim("email", email);
        }
        return builder.build();
    }

    private static Jwt resourceJwt(String audience, List<String> scopes) {
        return Jwt.withTokenValue("resource-token")
                .header("alg", "RS256")
                .subject("central-user-id")
                .claim("uid", "central-user-id")
                .claim("email", "ann@example.com")
                .audience(List.of(audience))
                .claim("scope", scopes)
                .build();
    }

    private static MockServerWebExchange exchange(String authorization, String spoofedUserId) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/kanban-backend/v1/boards");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (spoofedUserId != null) {
            request.header("X-User-Id", spoofedUserId);
        }
        return MockServerWebExchange.from(request);
    }

    @Test
    void validTokenForwardsIdentityAndReplacesClientSuppliedHeader() {
        when(decoder.decode("abc")).thenReturn(Mono.just(jwt("user-1", "ann@example.com")));

        filter.filter(exchange("Bearer abc", "attacker-id"), chain).block();

        assertNotNull(forwarded.get());
        var headers = forwarded.get().getRequest().getHeaders();
        assertEquals(List.of("user-1"), headers.get("X-User-Id"));
        assertEquals(List.of("ann@example.com"), headers.get("X-User-Email"));
    }

    @Test
    void configuredResourceAudienceAndMethodScopesAreEnforced() {
        var config = new AuthenticationFilter.Config();
        config.setAudience("https://api.example.com/kanban");
        config.setReadScope("kanban.read");
        config.setWriteScope("kanban.write");
        GatewayFilter protectedFilter = new AuthenticationFilter(decoder).apply(config);
        when(decoder.decode("read-only")).thenReturn(Mono.just(
                resourceJwt("https://api.example.com/kanban", List.of("kanban.read"))));
        when(decoder.decode("wrong-audience")).thenReturn(Mono.just(
                resourceJwt("https://api.example.com/ipon", List.of("kanban.read", "kanban.write"))));

        MockServerWebExchange read = MockServerWebExchange.from(
                MockServerHttpRequest.get("/kanban-backend/v1/boards").header("Authorization", "Bearer read-only"));
        protectedFilter.filter(read, chain).block();
        assertNotNull(forwarded.get());
        assertEquals(200, read.getResponse().getStatusCode() == null ? 200 : read.getResponse().getStatusCode().value());

        forwarded.set(null);
        MockServerWebExchange write = MockServerWebExchange.from(
                MockServerHttpRequest.post("/kanban-backend/v1/boards").header("Authorization", "Bearer read-only"));
        protectedFilter.filter(write, chain).block();
        assertNull(forwarded.get());
        assertEquals(403, write.getResponse().getStatusCode().value());
        assertEquals("Bearer error=\\"insufficient_scope\\", scope=\\"kanban.write\\"",
                write.getResponse().getHeaders().getFirst("WWW-Authenticate"));

        MockServerWebExchange mismatch = MockServerWebExchange.from(
                MockServerHttpRequest.get("/kanban-backend/v1/boards").header("Authorization", "Bearer wrong-audience"));
        protectedFilter.filter(mismatch, chain).block();
        assertNull(forwarded.get());
        assertEquals(401, mismatch.getResponse().getStatusCode().value());
    }

    @Test
    void missingAuthorizationHeaderIsRejected() {
        MockServerWebExchange exchange = exchange(null, null);

        filter.filter(exchange, chain).block();

        assertNull(forwarded.get());
        assertEquals(401, exchange.getResponse().getStatusCode().value());
        assertEquals("Bearer", exchange.getResponse().getHeaders().getFirst("WWW-Authenticate"));
    }

    @Test
    void invalidTokenIsRejected() {
        when(decoder.decode("bad")).thenReturn(Mono.error(new BadJwtException("bad signature")));
        MockServerWebExchange exchange = exchange("Bearer bad", null);

        filter.filter(exchange, chain).block();

        assertNull(forwarded.get());
        assertEquals(401, exchange.getResponse().getStatusCode().value());
        assertEquals("Bearer error=\"invalid_token\"", exchange.getResponse().getHeaders().getFirst("WWW-Authenticate"));
    }

    @Test
    void challengePointsToTheResourceMetadataWhenConfigured() {
        var config = new AuthenticationFilter.Config();
        config.setResourceMetadata("https://api.ikoyski.top/.well-known/oauth-protected-resource/kanban-mcp/mcp");
        GatewayFilter withMetadata = new AuthenticationFilter(decoder).apply(config);
        when(decoder.decode("bad")).thenReturn(Mono.error(new BadJwtException("expired")));

        MockServerWebExchange noToken = exchange(null, null);
        withMetadata.filter(noToken, chain).block();
        MockServerWebExchange badToken = exchange("Bearer bad", null);
        withMetadata.filter(badToken, chain).block();

        String url = "resource_metadata=\"https://api.ikoyski.top/.well-known/oauth-protected-resource/kanban-mcp/mcp\"";
        assertEquals("Bearer " + url, noToken.getResponse().getHeaders().getFirst("WWW-Authenticate"));
        assertEquals("Bearer error=\"invalid_token\", " + url, badToken.getResponse().getHeaders().getFirst("WWW-Authenticate"));
    }

    @Test
    void tokenWithoutUidOrEmailIsRejected() {
        when(decoder.decode("nouid")).thenReturn(Mono.just(jwt(null, "ann@example.com")));
        when(decoder.decode("noemail")).thenReturn(Mono.just(jwt("user-1", null)));

        MockServerWebExchange first = exchange("Bearer nouid", null);
        filter.filter(first, chain).block();
        MockServerWebExchange second = exchange("Bearer noemail", null);
        filter.filter(second, chain).block();

        assertNull(forwarded.get());
        assertEquals(401, first.getResponse().getStatusCode().value());
        assertEquals(401, second.getResponse().getStatusCode().value());
    }
}
