package ai.cc.chongming.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.cc.chongming.auth.application.AuthService.AuthResult;
import ai.cc.chongming.auth.config.AuthProperties;
import ai.cc.chongming.auth.domain.AuthErrorCode;
import ai.cc.chongming.auth.domain.AuthException;
import ai.cc.chongming.auth.domain.User;
import ai.cc.chongming.auth.domain.UserRepository;
import ai.cc.chongming.auth.infrastructure.InMemoryUserRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavior tests for login and registration against the in-memory user store.
 *
 * @author wangli
 */
class AuthServiceTests {

    private InMemoryUserRepository userRepository;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = new InMemoryUserRepository();
        PasswordHasher passwordHasher = new PasswordHasher();
        JwtTokenService tokenService = new JwtTokenService(
                new AuthProperties(true, "chongming-test-jwt-secret-0123456789abcdef", Duration.ofHours(1)));
        authService = new AuthService(userRepository, passwordHasher, tokenService);
    }

    @Test
    void registeredUserCanLogInWithIssuedToken() {
        AuthResult registration = authService.register("bob", "password123", "Bob", null);

        assertThat(registration.token()).isNotBlank();
        assertThat(registration.expiresAt()).isNotNull();
        assertThat(registration.user().username()).isEqualTo("bob");
        assertThat(registration.user().displayName()).isEqualTo("Bob");
        // [AIREVIEW-PLAN-027] Registration without a role defaults to developer-level access.
        assertThat(registration.user().role()).isEqualTo("DEVELOPER");

        AuthResult login = authService.login("bob", "password123");
        assertThat(login.token()).isNotBlank();
        assertThat(login.user().username()).isEqualTo("bob");
    }

    @Test
    void rejectsWrongPasswordWithGenericMessage() {
        authService.register("bob", "password123", "Bob", null);

        assertThatThrownBy(() -> authService.login("bob", "wrong-password"))
                .isInstanceOfSatisfying(AuthException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIAL);
                    assertThat(ex.getMessage()).isEqualTo("用户名或密码错误");
                });
    }

    @Test
    void rejectsUnknownUserWithTheSameGenericMessageToPreventEnumeration() {
        authService.register("bob", "password123", "Bob", null);

        assertThatThrownBy(() -> authService.login("mallory", "password123"))
                .isInstanceOfSatisfying(AuthException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIAL);
                    assertThat(ex.getMessage()).isEqualTo("用户名或密码错误");
                });
    }

    @Test
    void rejectsDuplicateUsernameOnRegistration() {
        authService.register("bob", "password123", "Bob", null);

        assertThatThrownBy(() -> authService.register("bob", "another-pass", "Bobby", null))
                .isInstanceOfSatisfying(AuthException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(AuthErrorCode.USERNAME_TAKEN));
    }

    @Test
    void rejectsTooShortPasswordOnRegistration() {
        assertThatThrownBy(() -> authService.register("bob", "short", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(userRepository.findByUsername("bob")).isEmpty();
    }

    /**
     * [AIREVIEW-PLAN-027] Self-registration accepts only the whitelisted non-admin roles.
     */
    @Test
    void registrationAcceptsWhitelistedRoles() {
        assertThat(authService.register("pm-user", "password123", "PM", "PRODUCT_MANAGER").user().role())
                .isEqualTo("PRODUCT_MANAGER");
        assertThat(authService.register("project-user", "password123", "PJ", "PROJECT_MANAGER").user().role())
                .isEqualTo("PROJECT_MANAGER");
        assertThat(authService.register("dev-user", "password123", "Dev", "DEVELOPER").user().role())
                .isEqualTo("DEVELOPER");
        assertThat(authService.register("blank-role-user", "password123", "Blank", "  ").user().role())
                .isEqualTo("DEVELOPER");
    }

    /**
     * [AIREVIEW-PLAN-027] ADMIN can never be self-registered; every non-whitelisted role fails
     * the shared validation contract before any user row is created.
     */
    @Test
    void registrationRejectsAdminAndUnknownRoles() {
        assertThatThrownBy(() -> authService.register("wannabe-admin", "password123", "Admin", "ADMIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role must be one of");
        assertThatThrownBy(() -> authService.register("legacy-user", "password123", "Legacy", "USER"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(userRepository.findByUsername("wannabe-admin")).isEmpty();
        assertThat(userRepository.findByUsername("legacy-user")).isEmpty();
    }

    @Test
    void unknownUserAndWrongPasswordBothRunTheVerifierExactlyOnce() {
        PasswordHasher countingHasher = mock(PasswordHasher.class);
        List<String> verifiedHashes = new ArrayList<>();
        when(countingHasher.verify(anyString(), anyString())).thenAnswer(invocation -> {
            verifiedHashes.add(invocation.getArgument(1, String.class));
            return false;
        });
        User knownUser = new User(1L, "bob", "PBKDF2$210000$salt$hash", "Bob", "USER");
        UserRepository userStore = mock(UserRepository.class);
        when(userStore.findByUsername("bob")).thenReturn(Optional.of(knownUser));
        when(userStore.findByUsername("mallory")).thenReturn(Optional.empty());
        JwtTokenService tokenService = new JwtTokenService(
                new AuthProperties(true, "chongming-test-jwt-secret-0123456789abcdef", Duration.ofHours(1)));
        AuthService service = new AuthService(userStore, countingHasher, tokenService);

        assertThatThrownBy(() -> service.login("bob", "wrong-password"))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> service.login("mallory", "password123"))
                .isInstanceOf(AuthException.class);

        // One verify per attempt on both paths; the unknown-user path must compare against a
        // well-formed PBKDF2 dummy hash so the derivation cost matches the real path.
        assertThat(verifiedHashes).hasSize(2);
        assertThat(verifiedHashes.get(0)).isEqualTo(knownUser.passwordHash());
        assertThat(verifiedHashes.get(1)).startsWith("PBKDF2$");
    }
}
