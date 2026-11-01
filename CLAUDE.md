# CLAUDE.md

Guidance for working in **webtools-api-gateway**: a Spring Cloud Gateway (reactive) service that is the edge entry point for the Webtools microservices. See `README.md` for the user-facing overview.

## Commands

```bash
./mvnw clean verify                  # full build (CI uses `mvn -B clean verify`)
./mvnw clean package -DskipTests     # jar only (target/webtools-api-gateway-0.0.1-SNAPSHOT.jar)
./mvnw spring-boot:run               # run, default profile
./mvnw spring-boot:run -Dspring-boot.run.profiles=private
./mvnw test                          # run gateway tests
docker compose up -d redis           # local Redis for the rate limiter
```

Running needs a Config Server (default `http://localhost:8089`, `user`/`password`). Without one the app starts (the import is `optional:`) but has no routes and **fails at startup** because `security.oauth2.issuer` / `security.oauth2.jwk-set-uri` are unresolved.

## Stack

Java 21, Spring Boot 3.4.2, Spring Cloud 2024.0.0, WebFlux/Netty, Eureka client, Config client, Resilience4j (reactor), reactive Redis, Micrometer Tracing (Brave) + Zipkin, Spring Security OAuth2 JOSE (JWKS verification).

## Architecture

Only four classes, all under `com.ikoyski.webtools.apigateway`:

- `WebtoolsApiGatewayApplication`: entry point.
- `config/RateLimiterConfig`: `ipKeyResolver` bean. Key = `CF-Connecting-IP` header, else remote address, else `"unknown"`.
- `controller/OAuthMetadataController`: serves RFC 9728 protected-resource metadata (public, no auth) from `security.oauth2.*` properties; a `@RestController`, so it wins over gateway routes and needs no route.
- `filter/AuthenticationFilter`: `AbstractGatewayFilterFactory`; validates `Authorization: Bearer <jwt>` through a `ReactiveJwtDecoder` (`config/JwtDecoderConfig`: RS256 only, JWKS from `security.oauth2.jwk-set-uri`, issuer `security.oauth2.issuer`). Route args can require `audience`, `readScope` and `writeScope`; safe methods require the read scope, mutating methods require the write scope. It replaces `X-User-Id`, `X-User-Email`, `X-User-Display-Name`, `X-User-Avatar-Url` and `X-User-Scopes` with values derived from verified claims. Missing/invalid token or audience mismatch returns 401; missing scope returns 403 `insufficient_scope`. `resourceMetadata` adds RFC 9728 metadata to the bearer challenge.
- `controller/FallbackController`: `/fallback/default` → `503`, `Retry-After: 20`, JSON body.

**There are no routes in this repo.** Routes, token issuer settings, port, Eureka, Redis and Zipkin settings come from the external Config Server. Changing routing behavior usually means editing the Config Server repo, not this one.

### Referencing beans from route config

- JWT filter: `- AuthenticationFilter` (the factory name is the class name, since it doesn't end in `GatewayFilterFactory`; renaming the class breaks every route that uses it).
- Protected app routes must configure all three resource-boundary args:
  ```yaml
  - name: AuthenticationFilter
    args:
      audience: https://api.example.com/kanban-backend
      readScope: kanban.read
      writeScope: kanban.write
  ```
  Match the audience exactly to the resource URI configured for that OAuth client in the Identity Server. Use separate API audiences and scope names for every app. The args are optional in code for legacy routes, so do not mistake an unconfigured filter for per-resource authorization.
- Rate limiter key: `key-resolver: "#{@ipKeyResolver}"`; renaming the bean breaks routes.
- Circuit breaker fallback: `fallbackUri: forward:/fallback/default`.

## Config and profiles

- `application.yaml`: default; Config Server at `localhost:8089`, hardcoded `user`/`password` (local dev only).
- `application-public.yaml` / `application-private.yaml`: identical; use `CONFIG_SERVER_URL`, `CONFIG_USER`, `CONFIG_PASSWORD`. Dockerfile activates `private`.
- App name: `webtools-api-gateway`. Do not put secrets or real URLs in these files.

## Conventions

- Reactive only. Use `Mono`/`Flux`; never block in filters, controllers or key resolvers (Netty event loop).
- Gateway filters are written as `AbstractGatewayFilterFactory` components with a nested `Config` class.
- Package root: `com.ikoyski.webtools.apigateway`; standard Maven layout; tabs/4-space mix exists in current files, so match the surrounding file.
- `pom.xml` is auto-sorted by `sortpom-maven-plugin` (`sort` goal, `verify` phase). Running `verify` may rewrite `pom.xml`; commit that result and don't hand-reorder.
- Keep `AuthenticationFilter` stateless and header-based; downstream services are expected to trust `X-User-*` only when deployed behind this gateway.
- Treat `uid` as the central identity UUID (same identity as JWT `sub`), not a Kanban-specific user ID.
- Configure exact per-route audience and read/write scopes for every protected first-party application. Never use one app's audience or scopes on another app's route.
- Tests live under `src/test/java`; currently two test classes cover gateway filter/config behavior. Add regression tests for every authorization or routing change using `spring-boot-starter-test` and `reactor-test`/`WebTestClient` as appropriate.

## Gotchas

- **Port mismatch**: Dockerfile `EXPOSE 8080`; Kubernetes manifest uses `8190`; the real port is `server.port` from the Config Server. Keep them in sync when changing any.
- **`X-User-*` only trusted behind the filter**: routes without `AuthenticationFilter` forward client-supplied identity headers as-is.
- **`CF-Connecting-IP` is trusted blindly**: fine behind Cloudflare/cloudflared, spoofable otherwise. Don't weaken or remove without considering the exposure model.
- **JWT claim mapping**: user id from `uid` (the central UUID, also `sub`), email from `email`, display name from `name`, avatar from `picture`, scopes from `scope`. If the Identity Server changes its claims, update the filter and resource servers in the same change. A token missing uid/email gets a 401.
- Keep only `spring-security-oauth2-jose` on the classpath. Adding `spring-boot-starter-oauth2-resource-server`, `spring-boot-starter-security` or `spring-security-config` switches on Spring Security's default filter chain in front of every route and breaks the gateway.
- The decoder accepts RS256 only, so a forged HS256 token can never be accepted; don't widen it.

## CI/CD

- GitHub Actions (`.github/workflows/maven.yml`): on push **and PR** to `master`: build, then push `ikoyski/webtools-api-gateway:latest` to Docker Hub. Note PRs also publish `:latest`.
- Jenkins (`Jenkinsfile`): build → push same `:latest` image → SCP `Deploy-webtools-api-gateway-private.yaml` to the K8s host and `kubectl apply`.
- Kubernetes: 1 replica, NodePort `8190`, `imagePullPolicy: Always`; no probes, limits, or env vars defined (config-server credentials must be supplied separately).
- Changes to the image tag, port, or required env vars must be updated in the Dockerfile, the Deployment YAML, and both pipelines.
