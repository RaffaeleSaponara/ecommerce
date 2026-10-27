package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.dto.QuoteLineDTO;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class QuoteSignerTests {
    private static final String KEY = "dGVzdC1vbmx5LWNoZWNrb3V0LWtleS0zMi1ieXRlcyEhISE=";
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"),ZoneOffset.UTC);
    private QuoteSigner.Payload payload() {
        var line = new QuoteLineDTO(7L,"Jig",new BigDecimal("12.30"),2,new BigDecimal("24.60"));
        return new QuoteSigner.Payload(1,41L,clock.instant().plusSeconds(300).getEpochSecond(),"EUR",
                new BigDecimal("24.60"),List.of(line));
    }
    @Test void signedQuoteSurvivesRestartWithStableConfiguredKey() {
        String token = new QuoteSigner(KEY,clock).sign(payload());
        assertThat(new QuoteSigner(KEY,clock).verify(token,41L)).isEqualTo(payload());
    }
    @Test void rejectsTamperingWrongOwnerPaddingMalformedAndOversizedTokens() {
        QuoteSigner signer = new QuoteSigner(KEY,clock); String token = signer.sign(payload());
        String[] parts = token.split("\\.");
        String replacedPrice = new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8)
                .replace("12.30","0.01");
        for (String invalid : List.of("x"+token, "invalid", parts[0]+"."+parts[1]+"=",
                Base64.getUrlEncoder().withoutPadding().encodeToString(replacedPrice.getBytes(StandardCharsets.UTF_8))+"."+parts[1],
                "x".repeat(QuoteSigner.MAX_TOKEN_LENGTH+1))) {
            assertThatThrownBy(() -> signer.verify(invalid,41L)).isInstanceOfSatisfying(CheckoutException.class,
                    e -> assertThat(e.getStatus().value()).isEqualTo(400));
        }
        assertThatThrownBy(() -> signer.verify(token,42L)).isInstanceOf(CheckoutException.class);
    }
    @Test void expiredAndRotatedQuotesAreRejected() {
        String token = new QuoteSigner(KEY,clock).sign(payload());
        assertThatThrownBy(() -> new QuoteSigner(KEY,Clock.offset(clock,Duration.ofSeconds(300))).verify(token,41L))
                .isInstanceOfSatisfying(CheckoutException.class,e -> assertThat(e.getStatus().value()).isEqualTo(409));
        byte[] different = new byte[32]; Arrays.fill(different,(byte)9);
        assertThatThrownBy(() -> new QuoteSigner(Base64.getEncoder().encodeToString(different),clock).verify(token,41L))
                .isInstanceOf(CheckoutException.class);
    }
    @Test void noFallbackSigningKeyAndNoWeakOrInvalidConfiguration() {
        assertThatThrownBy(() -> new QuoteSigner("",clock).sign(payload()))
                .isInstanceOfSatisfying(CheckoutException.class,e -> assertThat(e.getStatus().value()).isEqualTo(503));
        assertThatThrownBy(() -> new QuoteSigner("not base64!",clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuoteSigner(Base64.getEncoder().encodeToString(new byte[31]),clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void exactUtf8KeyBytesWithoutNormalizationTrimmingOrCaseFolding() {
        assertThat(CheckoutService.keyBytes(" Key ")).containsExactly(" Key ".getBytes(StandardCharsets.UTF_8));
        assertThat(CheckoutService.keyBytes("Key")).isNotEqualTo(CheckoutService.keyBytes("key"));
        assertThat(CheckoutService.keyBytes("\u00e9")).containsExactly("\u00e9".getBytes(StandardCharsets.UTF_8));
        assertThat(CheckoutService.keyBytes("\u00e9".repeat(64))).hasSize(128);
        for (String invalid : List.of("", "\u00e9".repeat(65), "x".repeat(129), String.valueOf((char)0xD800)))
            assertThatThrownBy(() -> CheckoutService.keyBytes(invalid)).isInstanceOf(CheckoutException.class);
        assertThatThrownBy(() -> CheckoutService.keyBytes(null)).isInstanceOf(CheckoutException.class);
    }
    @Test void requestFingerprintIsRealSha256OfVersionedCanonicalPayload() throws Exception {
        String token = new QuoteSigner(KEY,clock).sign(payload());
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(
                ("3DL-CHECKOUT-V2\nconfirmPrices=true\nquoteToken="+token).getBytes(StandardCharsets.UTF_8));
        assertThat(QuoteSigner.requestHash(token)).containsExactly(expected).hasSize(32);
        assertThat(QuoteSigner.requestHash(token+"x")).isNotEqualTo(expected);
    }
}
