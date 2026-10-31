package com.product.activation.infrastructure;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.*;

class JwtValidationTest {
    HttpServer server;
    RSAKey key;
    JwtDecoder decoder;

    @BeforeEach
    void setup() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test").generate();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/jwks", exchange -> {
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        decoder = new SecurityConfiguration().jwtDecoder("https://issuer.example", "http://localhost:" + server.getAddress().getPort() + "/jwks", "activation-api");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    String token(String issuer, String audience, String subject, Instant expires, RSAKey signingKey) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject);
        if (expires != null) claims.expirationTime(Date.from(expires));
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims.build());
        token.sign(new RSASSASigner(signingKey));
        return token.serialize();
    }

    @Test
    void validatesSignatureIssuerAudienceExpirationAndConsumerIdentity() throws Exception {
        var future = Instant.now().plusSeconds(3600);
        String valid = token("https://issuer.example", "activation-api", "consumer-a", future, key);
        assertThat(decoder.decode(valid).getSubject()).isEqualTo("consumer-a");
        String wrongIssuer = token("https://other.example", "activation-api", "consumer-a", future, key);
        String wrongAudience = token("https://issuer.example", "other-api", "consumer-a", future, key);
        String expired = token("https://issuer.example", "activation-api", "consumer-a", Instant.now().minusSeconds(120), key);
        String noSubject = token("https://issuer.example", "activation-api", null, future, key);
        String noExpiration = token("https://issuer.example", "activation-api", "consumer-a", null, key);
        String noAudience = token("https://issuer.example", null, "consumer-a", future, key);
        String forged = token("https://issuer.example", "activation-api", "consumer-a", future, new RSAKeyGenerator(2048).generate());
        for (String invalid : new String[]{wrongIssuer, wrongAudience, expired, noSubject, noExpiration, noAudience, forged}) {
            assertThatThrownBy(() -> decoder.decode(invalid)).isInstanceOf(JwtException.class);
        }
    }
}
