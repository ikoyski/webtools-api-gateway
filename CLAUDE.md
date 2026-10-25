# CLAUDE.md

Guidance for working in **webtools-api-gateway**: a Spring Cloud Gateway (reactive) service that is the edge entry point for the Webtools microservices. See `README.md` for the user-facing overview.

## Commands

```bash
./mvnw clean verify                  # full build (CI uses `mvn -B clean verify`)
./mvnw clean package -DskipTests     # jar only (target/webtools-api-gateway-0.0.1-SNAPSHOT.jar)
./mvnw spring-boot:run               # run, default profile
./mvnw spring-boot:run -Dspring-boot.run.profiles=private
./mvnw test                          # no tests exist yet (src/test is absent)
docker compose up -d redis           # local Redis for the rate limiter
```

Running needs a Config Server (default `http://localhost:8089`, `user`/`password`). Without one the app starts (the import is `optional:`) but has no routes and **fails at startup** because `security.jwt.token.secret-key` is unresolved.

## Stack

Java 21, Spring Boot 3.4.2, Spring Cloud 2024.0.0, WebFlux/Netty, Eureka client, Config client, Resilience4j (reactor), reactive Redis, Micrometer Tracing (Brave) + Zipkin, Spring Security OAuth2 JOSE (JWKS verification).

## Architecture

Only four classes, all under `com.ikoyski.webtools.apigateway`:

- `WebtoolsApiGatewayApplication`: entry point.
- `config/RateLimiterConfig`: `ipKeyResolver` bean. Key = `CF-Connecting-IP` header, else remote address, else `"unknown"`.
- `filter/AuthenticationFilter`: `AbstractGatewayFilterFactory`; validates `Authorization: Bearer <jwt>` through a `ReactiveJwtDecoder` (`config/JwtDecoderConfig`: RS256 only, keys from `security.oauth2.jwk-set-uri`, issuer `security.oauth2.issuer`), sets `X-User-Email` (JWT `email`) and `X-User-Id` (JWT `uid`) on the forwarded request. Any failure returns a `401` with `WWW-Authenticate: Bearer`.
- `controller/FallbackController`: `/fallback/default` → `503`, `Retry-After: 20`, JSON body.

**There are no routes in this repo.** Routes, token issuer settings, port, Eureka, Redis and Zipkin settings come from the external Config Server. Changing routing behavior usually means editing the Config Server repo, not this one.

### Referencing beans from route config

- JWT filter: `- AuthenticationFilter` (the factory name is the class name, since it doesn't end in `GatewayFilterFactory`; renaming the class breaks every route that uses it).
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
- Keep `AuthenticationFilter` stateless and header-based; downstream services are expected to trust `X-User-*` and not parse JWTs.
- Add tests under `src/test/java` using `spring-boot-starter-test` (the only test dependency; add `reactor-test`/`WebTestClient` support as needed).

## Gotchas

- **Port mismatch**: Dockerfile `EXPOSE 8080`; Kubernetes manifest uses `8190`; the real port is `server.port` from the Config Server. Keep them in sync when changing any.
- **`X-User-*` only trusted behind the filter**: routes without `AuthenticationFilter` forward client-supplied identity headers as-is.
- **`CF-Connecting-IP` is trusted blindly**: fine behind Cloudflare/cloudflared, spoofable otherwise. Don't weaken or remove without considering the exposure model.
- **JWT claim mapping**: user id from `uid`, email from `email` (not `sub`, which is also the user id, and not `jti`, which is just a token id). If the auth server changes its claims, update the filter in the same change. A token missing either claim gets a `401`.
- **Secret length**: HS256 key must be at least 32 bytes or `Keys.hmacShaKeyFor` throws at startup.
- Keep only `spring-security-oauth2-jose` on the classpath. Adding `spring-boot-starter-oauth2-resource-server`, `spring-boot-starter-security` or `spring-security-config` switches on Spring Security's default filter chain in front of every route and breaks the gateway.
- The decoder accepts RS256 only, so a forged HS256 token can never be accepted; don't widen it.

## CI/CD

- GitHub Actions (`.github/workflows/maven.yml`): on push **and PR** to `master`: build, then push `ikoyski/webtools-api-gateway:latest` to Docker Hub. Note PRs also publish `:latest`.
- Jenkins (`Jenkinsfile`): build → push same `:latest` image → SCP `Deploy-webtools-api-gateway-private.yaml` to the K8s host and `kubectl apply`.
- Kubernetes: 1 replica, NodePort `8190`, `imagePullPolicy: Always`; no probes, limits, or env vars defined (config-server credentials must be supplied separately).
- Changes to the image tag, port, or required env vars must be updated in the Dockerfile, the Deployment YAML, and both pipelines.
