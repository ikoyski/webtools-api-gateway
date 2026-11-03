package com.ikoyski.webtools.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Verifies access tokens issued by the auth server: RS256 signature (public keys fetched from the JWKS
 * and cached, refetched when an unknown key id appears), expiry/not-before, and the issuer.
 *
 * JWKS requests use a load-balanced WebClient so an internal Eureka service ID (for example,
 * http://WEBTOOLS-AUTH/oauth2/jwks) is resolved through Eureka rather than ordinary DNS.
 *
 * The decoder is a bean so Spring Boot does not generate a default user and password (it backs off when
 * a ReactiveJwtDecoder exists).
 */
@Configuration
public class JwtDecoderConfig {

    @Bean
    @LoadBalanced
    public WebClient.Builder jwksWebClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder(
            @Value("${security.oauth2.issuer}") String issuer,
            @Value("${security.oauth2.jwk-set-uri}") String jwkSetUri,
            @LoadBalanced WebClient.Builder jwksWebClientBuilder) {

        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)   // only RS256: HS* tokens can never be accepted
                .webClient(jwksWebClientBuilder.build())
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }
}
