
package com.ikoyski.webtools.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class JwtDecoderConfig {

    @Bean
    public WebClient.Builder jwksWebClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder(
            @Value("${security.oauth2.issuer}") String issuer,
            @Value("${security.oauth2.jwk-set-uri}") String jwkSetUri,
            WebClient.Builder jwksWebClientBuilder) {

        NimbusReactiveJwtDecoder decoder =
                NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri)
                        .jwsAlgorithm(SignatureAlgorithm.RS256)
                        .webClient(jwksWebClientBuilder.build())
                        .build();

        decoder.setJwtValidator(
                JwtValidators.createDefaultWithIssuer(issuer));

        return decoder;
    }
}
