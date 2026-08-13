package ai.cc.chongming.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.auth.application.JwtTokenService.AuthPrincipal;
import ai.cc.chongming.auth.application.JwtTokenService.IssuedToken;
import ai.cc.chongming.auth.config.AuthProperties;
import ai.cc.chongming.auth.domain.AuthErrorCode;
import ai.cc.chongming.auth.domain.AuthException;
import ai.cc.chongming.auth.domain.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Behavior tests for JWT issuance, parsing, expiry and tamper rejection.
 *
 * @author wangli
 */
class JwtTokenServiceTests {

    private static final String SECRET = "chongming-test-jwt-secret-0123456789abcdef";
    private static final Duration TTL = Duration.ofHours(1);
    private static final Instant BASE_TIME = Instant.parse("2026-08-12T00:00:00Z");

    private final User user = new User(1L, "alice", "PBKDF2$210000$salt$hash", "Alice", "USER");

    private JwtTokenService serviceAt(Instant now) {
        return new JwtTokenService(new AuthProperties(true, SECRET, TTL), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void issueThenParseRoundTripsPrincipal() {
        JwtTokenService service = serviceAt(BASE_TIME);

        IssuedToken issued = service.issue(user);
        AuthPrincipal principal = service.parse(issued.token());

        assertThat(principal.username()).isEqualTo("alice");
        assertThat(principal.displayName()).isEqualTo("Alice");
        assertThat(principal.role()).isEqualTo("USER");
        assertThat(issued.expiresAt()).isEqualTo(BASE_TIME.plus(TTL));
    }

    @Test
    void rejectsExpiredToken() {
        JwtTokenService issuer = serviceAt(BASE_TIME);
        JwtTokenService verifier = serviceAt(BASE_TIME.plus(TTL).plusSeconds(1));
        IssuedToken issued = issuer.issue(user);

        assertThatThrownBy(() -> verifier.parse(issued.token()))
                .isInstanceOfSatisfying(AuthException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(AuthErrorCode.UNAUTHENTICATED));
    }

    @Test
    void rejectsTamperedToken() {
        JwtTokenService service = serviceAt(BASE_TIME);
        IssuedToken issued = service.issue(user);
        char last = issued.token().charAt(issued.token().length() - 1);
        String tampered = issued.token().substring(0, issued.token().length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThatThrownBy(() -> service.parse(tampered))
                .isInstanceOfSatisfying(AuthException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(AuthErrorCode.UNAUTHENTICATED));
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        JwtTokenService otherService =
                new JwtTokenService(new AuthProperties(true, "another-secret-long-enough-for-hs256-ok", TTL),
                        Clock.fixed(BASE_TIME, ZoneOffset.UTC));
        JwtTokenService service = serviceAt(BASE_TIME);
        IssuedToken foreignToken = otherService.issue(user);

        assertThatThrownBy(() -> service.parse(foreignToken.token()))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void failsFastWhenSecretIsMissingOrTooShort() {
        assertThatThrownBy(() -> new JwtTokenService(new AuthProperties(true, "", TTL)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-secret");
        assertThatThrownBy(() -> new JwtTokenService(new AuthProperties(true, "short", TTL)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void rejectsMissingTokenValue() {
        JwtTokenService service = serviceAt(BASE_TIME);

        assertThatThrownBy(() -> service.parse(" "))
                .isInstanceOfSatisfying(AuthException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(AuthErrorCode.UNAUTHENTICATED));
    }
}
