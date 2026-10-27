package com.TreDL.ecommerce.security;

import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class JwtSecurityTests {
    private final UserDetails user = User.withUsername("jwt-test")
            .password("unused").authorities("ROLE_USER").build();

    private static String newSecret() {
        return Base64.getEncoder().encodeToString(
                Keys.secretKeyFor(SignatureAlgorithm.HS256).getEncoded());
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void contextRequiresExplicitSecret() {
        new ApplicationContextRunner().withUserConfiguration(JwtUtil.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "JWT_SECRET_BASE64 must be configured");
        });
    }

    @Test
    void rejectsMalformedAndShortSecrets() {
        assertThatThrownBy(() -> new JwtUtil("not base64!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JWT_SECRET_BASE64 must be valid Base64");
        assertThatThrownBy(() -> new JwtUtil(Base64.getEncoder().encodeToString(new byte[31])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JWT_SECRET_BASE64 must encode at least 32 bytes");
    }

    @Test
    void configuredKeyWorksAcrossInstancesAndRotationRejectsOldTokens() {
        String secret = newSecret();
        String token = new JwtUtil(secret).generateToken(user);
        JwtUtil restarted = new JwtUtil(secret);
        assertThat(restarted.extractUsername(token)).isEqualTo(user.getUsername());
        assertThat(restarted.validateToken(token, user)).isTrue();
        assertThatThrownBy(() -> new JwtUtil(newSecret()).extractUsername(token))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void oldAndMalformedCookiesContinueWithoutAuthentication() throws Exception {
        String oldToken = new JwtUtil(newSecret()).generateToken(user);
        UserDetailsService users = mock(UserDetailsService.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(new JwtUtil(newSecret()), users);
        for (String token : new String[]{oldToken, "invalid-token"}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setCookies(new Cookie("jwt", token));
            AtomicBoolean continued = new AtomicBoolean();
            filter.doFilter(request, new MockHttpServletResponse(),
                    (req, res) -> continued.set(true));
            assertThat(continued.get()).isTrue();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
        verifyNoInteractions(users);
    }
}
