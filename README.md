# Webtools API Gateway

The Webtools API Gateway is a reactive entry point for the Webtools ecosystem, built using **Spring Cloud Gateway**. It manages routing, rate limiting, and observability for downstream services.

## Features

- **Dynamic Routing**: Integrated with **Netflix Eureka** for service discovery and **Spring Cloud Config** for centralized route management.
- **Rate Limiting**: IP-based rate limiting implemented using **Redis** to prevent abuse and ensure service stability.
- **Observability**: Distributed tracing enabled via **Zipkin** and **Brave**, with health and metrics provided by **Spring Boot Actuator**.
- **Modern Stack**: Built with **Java 21** and **Spring Boot 3.4.2**.

## Getting Started

### Prerequisites

- **Java 21** (JDK)
- **Maven** (provided via `./mvnw` wrapper)

### Building the Project

To compile and package the application:

```bash
./mvnw clean package
```

### Running the Application

To start the gateway locally:

```bash
./mvnw spring-boot:run
```

The gateway will attempt to connect to the Config Server specified in `src/main/resources/application.yaml`.

## Configuration

The project uses Spring Cloud Config for externalized configuration. The gateway identifies which config server to use based on the active profile:

- **Default**: `http://localhost:8089`
- **Public**: `https://webtools-config-server.onrender.com`
- **Private**: Defined in `application-private.yaml`

## Deployment

### Docker

You can build and run the application using Docker:

```bash
docker build -t webtools-api-gateway .
docker run -p 8080:8080 webtools-api-gateway
```

A `docker-compose.yml` is provided for orchestrating the gateway with its dependencies (e.g., Redis).

### CI/CD

The project includes a `Jenkinsfile` for automated builds and deployments.
