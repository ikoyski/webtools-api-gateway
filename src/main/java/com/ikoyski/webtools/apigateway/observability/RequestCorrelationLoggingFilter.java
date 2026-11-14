package com.ikoyski.webtools.apigateway.observability;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;

/**
 * Propagates a validated request ID across gateway routes and writes a safe
 * access summary. Bodies, query strings and authorization headers are never logged.
 */
@Component
public class RequestCorrelationLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationLoggingFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String suppliedId = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        String requestId = suppliedId != null && SAFE_REQUEST_ID.matcher(suppliedId).matches()
                ? suppliedId : UUID.randomUUID().toString();

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(REQUEST_ID_HEADER, requestId))
                .build();
        ServerWebExchange correlatedExchange = exchange.mutate().request(request).build();
        correlatedExchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);

        String method = request.getMethod() == null ? "UNKNOWN" : request.getMethod().name();
        String path = request.getURI().getRawPath();
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String routeId = route == null ? "unmatched" : route.getId();
        long startedAt = System.nanoTime();

        return chain.filter(correlatedExchange)
                .doOnError(ex -> log.atError()
                        .addKeyValue("requestId", requestId)
                        .addKeyValue("method", method)
                        .addKeyValue("path", path)
                        .addKeyValue("routeId", routeId)
                        .setCause(ex)
                        .log("gateway_request_failed"))
                .doFinally(signal -> {
                    HttpStatusCode responseStatus = correlatedExchange.getResponse().getStatusCode();
                    int status = responseStatus != null ? responseStatus.value()
                            : signal == SignalType.ON_ERROR ? 500 : 200;
                    long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;

                    var event = status >= 500 ? log.atError()
                            : status >= 400 ? log.atWarn() : log.atInfo();
                    event.addKeyValue("requestId", requestId)
                            .addKeyValue("method", method)
                            .addKeyValue("path", path)
                            .addKeyValue("routeId", routeId)
                            .addKeyValue("status", status)
                            .addKeyValue("durationMs", durationMs)
                            .log("gateway_request_completed");
                });
    }
}
