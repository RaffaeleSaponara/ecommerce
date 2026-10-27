package com.TreDL.ecommerce;

import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Base64;

public abstract class JwtTestSupport {
    private static final String SECRET = Base64.getEncoder().encodeToString(
            Keys.secretKeyFor(SignatureAlgorithm.HS256).getEncoded());

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> SECRET);
    }
}
