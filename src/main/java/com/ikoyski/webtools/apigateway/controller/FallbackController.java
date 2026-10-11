package com.ikoyski.webtools.apigateway.controller;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class FallbackController {

    @RequestMapping("/fallback/default")
    public Mono<ResponseEntity<Map<String, Object>>> fallbackDefault() {
        return Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "20")
                .body(Map.of(
                        "status", 503,
                        "error", "Service temporarily unavailable",
                        "message", "Please try again in a few seconds.")));
    }
}