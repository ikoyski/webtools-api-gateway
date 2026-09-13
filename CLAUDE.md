# Webtools API Gateway - Guide

## Build and Run
- Build: `./mvnw clean package`
- Run: `./mvnw spring-boot:run`
- Test: `./mvnw test`

## Code Style
- Java 21 / Spring Boot 3.4.2 / Spring Cloud 2024.0.0
- Reactive programming using Project Reactor (Mono/Flux)
- Standard Spring Boot project structure (`src/main/java`, `src/main/resources`)
- Package naming: `com.ikoyski.webtools.apigateway`
- Maven `pom.xml` is kept sorted using `sortpom-maven-plugin`
- Use `application.yaml` for base config, `application-public.yaml` and `application-private.yaml` for environment-specific config server imports
