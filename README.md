# Webtools API Gateway

Reactive edge gateway for the Webtools microservice ecosystem, built on **Spring Cloud Gateway** (WebFlux / Netty). It is the single public entry point: it authenticates callers with access tokens issued by the auth server, rate-limits (per client IP on public routes, per user on authenticated ones), routes to downstream services discovered through Eureka, and degrades gracefully when a service is down.

The gateway itself is small and holds no routes. **Route definitions, the token issuer settings, Redis settings and the server port all come from the Spring Cloud Config Server at startup** (see [Configuration](#configuration)).

## Features

| Capability | Implementation |
|---|---|
| Routing & service discovery | Spring Cloud Gateway + Netflix Eureka client (`lb://` URIs); routes defined in the Config Server |
| Authentication | `AuthenticationFilter` verifies RS256 access tokens from the auth server (public keys fetched from its JWKS) and forwards the user identity downstream |
| Rate limiting | Redis-backed `RequestRateLimiter` using the `ipKeyResolver` bean (per client IP) |
| Resilience | Resilience4j circuit breaker; `/fallback/default` returns `503` with `Retry-After: 20` |
| Observability | Spring Boot Actuator, Micrometer Tracing (Brave bridge), Zipkin reporter |
| Externalized config | Spring Cloud Config client, imported as an `optional:` config source |

**Stack:** Java 21 · Spring Boot 3.4.2 · Spring Cloud 2024.0.0 · Spring Security OAuth2 JOSE (JWKS) · Maven (wrapper included)

## Architecture

```
                        ┌──────────────────────────────────────────┐
  Client ──HTTPS──▶ ─── │  webtools-api-gateway                    │
 (Cloudflare tunnel     │                                          │
  or direct)            │  1. ipKeyResolver  → Redis rate limiter  │
                        │  2. AuthenticationFilter (JWT)           │
                        │  3. Route → lb://<service> via Eureka    │
                        │  4. CircuitBreaker → /fallback/default   │
                        └───────┬───────────────┬──────────────────┘
                                │               │
                   Config Server (startup)   Eureka / Redis / Zipkin
```

### Request flow

1. **Rate limit**: `RateLimiterConfig.ipKeyResolver` keys requests by the `CF-Connecting-IP` header when present, otherwise by the TCP remote address (`unknown` if unavailable).
2. **Authentication** (routes that opt in): `AuthenticationFilter` requires `Authorization: Bearer <jwt>`. It verifies the RS256 signature (against the auth server's JWKS), the expiry and the issuer, then sets two request headers for downstream services:
   - `X-User-Email` ← JWT `email` claim
   - `X-User-Id` ← JWT `uid` claim
   
   Missing, malformed, tampered or expired tokens get a bare `401 Unauthorized` (empty body).
3. **Routing**: the request is forwarded to the matching downstream service.
4. **Fallback**: if a route's circuit breaker trips, it forwards to `/fallback/default`:
   ```json
   { "status": 503, "error": "Service temporarily unavailable", "message": "Please try again in a few seconds." }
   ```
   with header `Retry-After: 20`.

> Downstream services should trust `X-User-Email` / `X-User-Id` **only** on routes protected by `AuthenticationFilter`, and should not be reachable except through the gateway. See [Security notes](#security-notes).

## Project structure

```
src/main/java/com/ikoyski/webtools/apigateway/
├── WebtoolsApiGatewayApplication.java     # Spring Boot entry point
├── config/RateLimiterConfig.java          # ipKeyResolver KeyResolver bean
├── controller/FallbackController.java     # /fallback/default (503 + Retry-After)
└── filter/AuthenticationFilter.java       # JWT gateway filter factory

src/main/resources/
├── application.yaml                       # default profile: local Config Server
├── application-public.yaml                # Config Server from env vars
└── application-private.yaml               # Config Server from env vars

Dockerfile · docker-compose.yml            # image build; local Redis
Jenkinsfile · .github/workflows/maven.yml  # CI/CD
Deploy-webtools-api-gateway-private.yaml   # Kubernetes Deployment + Service
```

There are currently no unit or integration tests (`src/test` does not exist).

## Getting started

### Prerequisites

- JDK 21
- A reachable **Spring Cloud Config Server** that serves `webtools-api-gateway` config (routes, token issuer settings, port, Eureka and Redis settings)
- **Redis** for rate limiting (`docker compose up -d redis` starts one on `6379`)
- Eureka and Zipkin if you want discovery-based routing and tracing locally

### Build and run

```bash
./mvnw clean verify          # build + (no) tests; also re-sorts pom.xml
./mvnw spring-boot:run       # run with the default profile
java -jar target/webtools-api-gateway-0.0.1-SNAPSHOT.jar   # run the packaged jar
```

With no profile set, the gateway looks for a Config Server at `http://localhost:8089` using `user` / `password`. Because the import is `optional:`, the app still starts if the server is unreachable, but it will then have **no routes and no JWT secret** and will fail on startup when `AuthenticationFilter` is created (the `security.jwt.token.secret-key` property is required).

## Configuration

### Profiles

| Profile | Config Server URL | Credentials |
|---|---|---|
| *(default)* | `http://localhost:8089` | `user` / `password` (hardcoded, local dev only) |
| `public` | `$CONFIG_SERVER_URL` | `$CONFIG_USER` / `$CONFIG_PASSWORD` |
| `private` | `$CONFIG_SERVER_URL` | `$CONFIG_USER` / `$CONFIG_PASSWORD` |

`public` and `private` are currently identical; they differ only in which environment you supply. The Docker image activates `private`.

```bash
export CONFIG_SERVER_URL=https://config.example.internal
export CONFIG_USER=...
export CONFIG_PASSWORD=...
./mvnw spring-boot:run -Dspring-boot.run.profiles=private
```

### Properties expected from the Config Server

| Property | Used by | Notes |
|---|---|---|
| `security.oauth2.issuer` | `JwtDecoderConfig` | **Required.** Issuer the tokens must carry in `iss`, for example `https://auth.ikoyski.top`. |
| `security.oauth2.jwk-set-uri` | `JwtDecoderConfig` | **Required.** Where to fetch the auth server's public keys, for example `http://<auth host>:8084/oauth2/jwks` (internal) or `https://auth.ikoyski.top/oauth2/jwks`. Fetched lazily and cached; the gateway starts even if the auth server is down. |
| `server.port` | Netty | Not set in this repo. Kubernetes manifest assumes **8190**. |
| `spring.cloud.gateway.routes[*]` | Gateway | Route definitions |
| `eureka.client.*` | Discovery | Service registry location |
| `spring.data.redis.*` | Rate limiter | Redis connection |
| `management.zipkin.tracing.endpoint`, `management.tracing.sampling.probability` | Tracing | Zipkin export |

### Example route

Routes live in the Config Server's `webtools-api-gateway*.yaml`. A typical protected route looks like:

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: kanban
          uri: lb://webtools-kanban
          predicates:
            - Path=/api/kanban/**
          filters:
            - AuthenticationFilter                 # JWT check, sets X-User-* headers
            - name: RequestRateLimiter
              args:
                key-resolver: "#{@ipKeyResolver}"
                redis-rate-limiter.replenishRate: 10
                redis-rate-limiter.burstCapacity: 20
            - name: CircuitBreaker
              args:
                name: kanbanCB
                fallbackUri: forward:/fallback/default
```

(Service name, path and limits above are illustrative; the real values are in the Config Server.)

A public route simply omits `AuthenticationFilter`.

## Docker

```bash
./mvnw clean package -DskipTests
docker build -t webtools-api-gateway .

docker run --rm -p 8190:8190 \
  -e CONFIG_SERVER_URL=... -e CONFIG_USER=... -e CONFIG_PASSWORD=... \
  webtools-api-gateway
```

- The image (`eclipse-temurin:21-jre-jammy`) copies `target/*.jar` and runs with `-Dspring.profiles.active=private`.
- The Dockerfile `EXPOSE`s `8080`, but the actual listening port is whatever `server.port` the Config Server provides (the Kubernetes manifest uses `8190`). Publish the port that matches your config.
- `docker-compose.yml` only provides Redis (`redis:7-alpine` on `6379`); it does not run the gateway.

## CI/CD

**GitHub Actions** (`.github/workflows/maven.yml`): on push or pull request to `master`, sets up JDK 21, runs `mvn -B clean verify`, then logs in to Docker Hub and pushes `ikoyski/webtools-api-gateway:latest`. Requires the `DOCKER_USERNAME` and `DOCKER_PASSWORD` secrets.

**Jenkins** (`Jenkinsfile`): checks out `master`, runs `mvn clean install`, builds and pushes the same `:latest` image, then copies `Deploy-webtools-api-gateway-private.yaml` to the Kubernetes host over SSH and runs `kubectl apply -f`. Requires the Jenkins credentials `Dockerhub-Credentials`, `K8s-Host-IP` and `K8s-Host-User-With-Key`.

**Kubernetes** (`Deploy-webtools-api-gateway-private.yaml`): a single-replica Deployment (`imagePullPolicy: Always`) and a `NodePort` Service, both on port `8190`. There are no probes, resource limits, or env vars in the manifest; `CONFIG_SERVER_URL`, `CONFIG_USER` and `CONFIG_PASSWORD` must be provided to the pod some other way (cluster-level config, or add them to the manifest via a Secret).

## Observability

- Actuator endpoints are exposed according to the Config Server's `management.*` settings.
- Traces are exported to Zipkin through Brave.

## Security notes

- **Spoofable client IP.** `ipKeyResolver` trusts the `CF-Connecting-IP` header unconditionally. That is safe only when the gateway is reachable exclusively through Cloudflare (e.g. a Cloudflare tunnel). If it is reachable directly, a client can rotate that header to evade rate limits.
- **Identity headers on unprotected routes.** Only routes with `AuthenticationFilter` overwrite `X-User-Email` / `X-User-Id`. On other routes, client-supplied values pass through untouched. Do not rely on them there.
- **Downstream exposure.** Services behind the gateway must not be directly reachable from untrusted networks, or the identity headers can be forged.
- **Secrets.** Config Server credentials must never be committed. There is no signing secret in the gateway: it only holds the auth server's public keys. The default profile's `user` / `password` is for local development only.
- **Token claims.** `AuthenticationFilter` reads the user ID from the `uid` claim and the email from the `email` claim. A token missing either, or not signed with RS256 by the configured issuer, gets a `401`.
