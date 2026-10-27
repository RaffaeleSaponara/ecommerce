package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.dto.QuoteLineDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

/** Versioned, canonical JSON protected by HMAC-SHA256. No random server key fallback. */
@Component
public class QuoteSigner {
    public static final int MAX_TOKEN_LENGTH = 65536;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final ObjectMapper mapper = new ObjectMapper();
    private final byte[] key;
    private final Clock clock;

    public record Payload(int version, long customerId, long expiresAt, String currency,
                          BigDecimal total, List<QuoteLineDTO> items) {}

    @Autowired
    public QuoteSigner(@Value("${checkout.quote-secret-base64:}") String secret) {
        this(secret, Clock.systemUTC());
    }

    public QuoteSigner(String secret, Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        if (secret == null || secret.isBlank()) {
            key = null; // Fail closed at the quote/checkout boundary without breaking unrelated pages.
        } else {
            try { key = Base64.getDecoder().decode(secret); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid checkout signing key", e); }
            if (key.length < 32) throw new IllegalArgumentException("Checkout signing key needs at least 32 bytes");
        }
    }

    public long expiresAt() { return clock.instant().plusSeconds(300).getEpochSecond(); }

    public String sign(Payload payload) {
        byte[] bytes = canonical(payload);
        String token = ENCODER.encodeToString(bytes) + "." + ENCODER.encodeToString(mac(bytes));
        if (token.length() > MAX_TOKEN_LENGTH) throw new CheckoutException(BAD_REQUEST, "Cart exceeds quote size limit");
        return token;
    }

    public Payload verify(String token, long customerId) {
        try {
            if (token == null || token.length() > MAX_TOKEN_LENGTH) throw new IllegalArgumentException();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            byte[] bytes = DECODER.decode(parts[0]);
            byte[] signature = DECODER.decode(parts[1]);
            if (!MessageDigest.isEqual(mac(bytes), signature)) throw new IllegalArgumentException();
            Payload payload = mapper.readValue(bytes, Payload.class);
            // Require one exact encoding, including field order, decimal scale and Base64 form.
            if (!token.equals(sign(payload)) || payload.version() != 1 || payload.customerId() != customerId)
                throw new IllegalArgumentException();
            requireUnexpired(payload);
            return payload;
        } catch (CheckoutException e) { throw e; }
        catch (Exception e) { throw new CheckoutException(BAD_REQUEST, "Invalid price confirmation"); }
    }

    public void requireUnexpired(Payload payload) {
        if (payload.expiresAt() <= clock.instant().getEpochSecond())
            throw new CheckoutException(CONFLICT, "Quote expired; request and confirm a new quote");
    }

    public static byte[] requestHash(String token) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH)
            throw new CheckoutException(BAD_REQUEST, "Quote token is required");
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    ("3DL-CHECKOUT-V2\nconfirmPrices=true\nquoteToken=" + token).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private byte[] canonical(Payload payload) {
        try { return mapper.writeValueAsBytes(payload); }
        catch (Exception e) { throw new IllegalStateException("Cannot encode quote", e); }
    }

    private byte[] mac(byte[] bytes) {
        if (key == null) throw new CheckoutException(SERVICE_UNAVAILABLE, "Price confirmation is not configured");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(bytes);
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
