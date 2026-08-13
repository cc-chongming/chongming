package ai.cc.chongming.auth.application;

import ai.cc.chongming.auth.config.AuthProperties;
import ai.cc.chongming.auth.domain.AuthErrorCode;
import ai.cc.chongming.auth.domain.AuthException;
import ai.cc.chongming.auth.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import javax.crypto.SecretKey;

/**
 * Issues and validates HS256 JWTs with jjwt 0.12. The signing key comes exclusively from the
 * {@code review.auth.jwt-secret} property (environment-injected); construction fails fast when
 * the secret is missing or shorter than the 256 bits HS256 requires, so a misconfigured
 * deployment never starts with unsigned-grade tokens.
 *
 * @author wangli
 */
public class JwtTokenService {

    /** HS256 mandates a key of at least 256 bits. */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey signingKey;
    private final Duration tokenTtl;
    private final Clock clock;

    public JwtTokenService(AuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /**
     * Package-visible constructor so tests can pin the clock for expiry scenarios.
     *
     * @param properties bound {@code review.auth.*} settings
     * @param clock      time source for issued-at and expiry claims
     */
    JwtTokenService(AuthProperties properties, Clock clock) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.tokenTtl = Objects.requireNonNull(properties.tokenTtl(), "review.auth.token-ttl must not be null");
        String secret = properties.jwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "review.auth.jwt-secret is not configured; provide REVIEW_AUTH_JWT_SECRET "
                            + "(at least 32 characters) or disable review.auth");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "review.auth.jwt-secret must be at least " + MIN_SECRET_BYTES
                            + " bytes for HS256 but was " + secretBytes.length);
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
    }

    /**
     * Signs a fresh token for an authenticated user.
     *
     * @param user authenticated user
     * @return compact token with its expiry instant
     */
    public IssuedToken issue(User user) {
        User nonNullUser = Objects.requireNonNull(user, "user must not be null");
        Instant issuedAt = Instant.now(clock);
        Instant expiresAt = issuedAt.plus(tokenTtl);
        var builder = Jwts.builder()
                .subject(nonNullUser.username())
                .claim("role", nonNullUser.role())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256);
        if (nonNullUser.displayName() != null) {
            builder.claim("displayName", nonNullUser.displayName());
        }
        return new IssuedToken(builder.compact(), expiresAt);
    }

    /**
     * Validates a token and extracts the authenticated principal.
     *
     * @param token compact JWT
     * @return principal carried by the token
     * @throws AuthException with {@link AuthErrorCode#UNAUTHENTICATED} on any rejection
     */
    public AuthPrincipal parse(String token) {
        if (token == null || token.isBlank()) {
            throw new AuthException(AuthErrorCode.UNAUTHENTICATED, "bearer token is missing");
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .clock(() -> Date.from(Instant.now(clock)))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String username = claims.getSubject();
            if (username == null || username.isBlank()) {
                throw new AuthException(AuthErrorCode.UNAUTHENTICATED, "token carries no subject");
            }
            return new AuthPrincipal(
                    username,
                    claims.get("displayName", String.class),
                    claims.get("role", String.class));
        } catch (JwtException | IllegalArgumentException ex) {
            throw new AuthException(AuthErrorCode.UNAUTHENTICATED, "bearer token is invalid or expired");
        }
    }

    /**
     * Compact token plus the instant it stops being valid.
     *
     * @author wangli
     */
    public record IssuedToken(String token, Instant expiresAt) {
    }

    /**
     * Principal extracted from a verified token.
     *
     * @author wangli
     */
    public record AuthPrincipal(String username, String displayName, String role) {
    }
}
