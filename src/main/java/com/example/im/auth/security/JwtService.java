package com.example.im.auth.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.im.common.exception.AuthException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class JwtService {

    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JwtProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public JwtService(JwtProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, Clock.systemUTC());
    }

    JwtService(JwtProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public TokenPair generate(Long userId) {
        long issuedAt = clock.instant().getEpochSecond();
        long expiresAt = issuedAt + properties.getTtlSeconds();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", properties.getIssuer());
        payload.put("sub", String.valueOf(userId));
        payload.put("userId", userId);
        payload.put("iat", issuedAt);
        payload.put("exp", expiresAt);

        String encodedHeader = encodeJson(header);
        String encodedPayload = encodeJson(payload);
        String signingInput = encodedHeader + "." + encodedPayload;
        String signature = BASE64_URL_ENCODER.encodeToString(sign(signingInput));
        return new TokenPair(signingInput + "." + signature, expiresAt);
    }

    public JwtClaims verify(String token) {
        if (token == null || token.isBlank()) {
            throw new AuthException("INVALID_TOKEN", "token is empty");
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new AuthException("INVALID_TOKEN", "token format is invalid");
        }

        String signingInput = parts[0] + "." + parts[1];
        byte[] expected = sign(signingInput);
        byte[] actual;
        try {
            actual = BASE64_URL_DECODER.decode(parts[2]);
        } catch (IllegalArgumentException exception) {
            throw new AuthException("INVALID_TOKEN", "token signature is invalid");
        }

        if (!MessageDigest.isEqual(expected, actual)) {
            throw new AuthException("INVALID_TOKEN", "token signature is invalid");
        }

        Map<String, Object> payload = decodeJson(parts[1]);
        if (!properties.getIssuer().equals(payload.get("iss"))) {
            throw new AuthException("INVALID_TOKEN", "token issuer is invalid");
        }

        long expiresAt = asLong(payload.get("exp"));
        long now = clock.instant().getEpochSecond();
        if (expiresAt <= now) {
            throw new AuthException("TOKEN_EXPIRED", "token is expired");
        }

        Long userId = asLong(payload.get("userId"));
        long issuedAt = asLong(payload.get("iat"));
        return new JwtClaims(userId, issuedAt, expiresAt);
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return BASE64_URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to encode jwt json", exception);
        }
    }

    private Map<String, Object> decodeJson(String encoded) {
        try {
            byte[] json = BASE64_URL_DECODER.decode(encoded);
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception exception) {
            throw new AuthException("INVALID_TOKEN", "token payload is invalid");
        }
    }

    private byte[] sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.getSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to sign jwt", exception);
        }
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string) {
            return Long.parseLong(string);
        }
        throw new AuthException("INVALID_TOKEN", "token numeric claim is invalid");
    }

    public record TokenPair(String token, long expiresAt) {
    }
}
