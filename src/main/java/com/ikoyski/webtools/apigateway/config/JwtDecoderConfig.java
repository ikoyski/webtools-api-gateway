package com.ikoyski.webtools.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * Verifies access tokens issued by the auth server: RS256 signature (public keys fetched from the JWKS
 * and cached, refetched when an unknown key id appears), expiry/not-before, and the issuer.
 *
 * The decoder is a bean so Spring Boot does not generate a default user and password (it backs off when
 * a ReactiveJwtDecoder exists).
 */
@Configuration
public class JwtDecoderConfig {

    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder(
            @Value("${security.oauth2.issuer}") String issuer,
            @Value("${security.oauth2.jwk-set-uri}") String jwkSetUri) {

        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)   // only RS256: HS* tokens can never be accepted
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }
}
