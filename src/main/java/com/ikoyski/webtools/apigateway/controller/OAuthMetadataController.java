package com.ikoyski.webtools.apigateway.controller;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * OAuth 2.0 Protected Resource Metadata (RFC 9728) for the MCP server that is published through this
 * gateway. An MCP client (for example the Claude connector) first calls the MCP URL without a token, gets a
 * 401 whose challenge points here, reads which authorization server issues tokens for this resource, and
 * then runs the sign-in there.
 *
 * Served at /.well-known/oauth-protected-resource (root fallback) and, per RFC 9728, with the resource's
 * path appended (for a resource https://api.ikoyski.top/kanban-mcp/mcp that is
 * /.well-known/oauth-protected-resource/kanban-mcp/mcp). It is public information, so no authentication.
 * Disabled (404) unless security.oauth2.protected-resource is set.
 */
@RestController
public class OAuthMetadataController {

    private static final String PREFIX = "/.well-known/oauth-protected-resource";

    private final String issuer;
    private final String resource;
    private final String resourcePath;
    private final List<String> scopes;

    public OAuthMetadataController(
            @Value("${security.oauth2.issuer}") String issuer,
            @Value("${security.oauth2.protected-resource:}") String resource,
            @Value("${security.oauth2.scopes:kanban.read,kanban.write}") List<String> scopes) {
        this.issuer = issuer;
        this.resource = resource == null ? "" : resource.trim();
        this.resourcePath = this.resource.isEmpty() ? "" : URI.create(this.resource).getPath();
        this.scopes = scopes;
    }

    @GetMapping({PREFIX, PREFIX + "/**"})
    public ResponseEntity<Map<String, Object>> protectedResource(ServerHttpRequest request) {
        if (resource.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String path = request.getPath().pathWithinApplication().value();
        String suffix = path.length() > PREFIX.length() ? path.substring(PREFIX.length()) : "";
        if (!suffix.isEmpty() && !suffix.equals(resourcePath)) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resource", resource);
        body.put("authorization_servers", List.of(issuer));
        body.put("scopes_supported", scopes);
        body.put("bearer_methods_supported", List.of("header"));
        body.put("resource_name", "Kanban MCP server");

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .header("Access-Control-Allow-Origin", "*")   // public document; lets browser-based MCP clients read it
                .body(body);
    }
}
