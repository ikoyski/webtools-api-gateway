package com.ikoyski.webtools.apigateway.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

class OAuthMetadataControllerTest {

    private static final String RESOURCE = "https://api.ikoyski.top/kanban-mcp/mcp";

    private final OAuthMetadataController controller = new OAuthMetadataController(
            "https://auth.ikoyski.top", RESOURCE, List.of("kanban.read", "kanban.write"));

    private ResponseEntity<Map<String, Object>> get(OAuthMetadataController c, String path) {
        return c.protectedResource(MockServerHttpRequest.get(path).build());
    }

    @Test
    void pathInsertedUrlReturnsTheDocument() {
        var response = get(controller, "/.well-known/oauth-protected-resource/kanban-mcp/mcp");

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(RESOURCE, body.get("resource"));
        assertEquals(List.of("https://auth.ikoyski.top"), body.get("authorization_servers"));
        assertEquals(List.of("kanban.read", "kanban.write"), body.get("scopes_supported"));
    }

    @Test
    void rootFallbackReturnsTheSameDocument() {
        var response = get(controller, "/.well-known/oauth-protected-resource");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(RESOURCE, response.getBody().get("resource"));
    }

    @Test
    void otherResourcePathsAreNotFound() {
        assertEquals(404, get(controller, "/.well-known/oauth-protected-resource/something/else").getStatusCode().value());
    }

    @Test
    void disabledWhenNoResourceIsConfigured() {
        var disabled = new OAuthMetadataController("https://auth.ikoyski.top", "", List.of("kanban.read"));
        assertEquals(404, get(disabled, "/.well-known/oauth-protected-resource").getStatusCode().value());
    }
}
