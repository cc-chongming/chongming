package ai.cc.chongming.auth.application;

import ai.cc.chongming.auth.application.JwtTokenService.IssuedToken;
import ai.cc.chongming.auth.domain.AuthErrorCode;
import ai.cc.chongming.auth.domain.AuthException;
import ai.cc.chongming.auth.domain.User;
import ai.cc.chongming.auth.domain.UserRepository;
import ai.cc.chongming.auth.domain.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Authentication commands: credential login and self-service registration. Login failures use
 * one shared message for unknown users and wrong passwords so responses never reveal which
 * usernames exist.
 *
 * @author wangli
 */
public class AuthService {

    private static final String INVALID_CREDENTIAL_MESSAGE = "用户名或密码错误";
    private static final int MAX_USERNAME_LENGTH = 64;
    private static final int MAX_DISPLAY_NAME_LENGTH = 64;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 128;

    /**
     * [AIREVIEW-PLAN-027] Roles a self-service registration may claim. Administrators are
     * deliberately excluded so ADMIN accounts can never be self-registered.
     */
    private static final List<String> REGISTERABLE_ROLES = List.of(
            UserRole.PRODUCT_MANAGER.code(),
            UserRole.PROJECT_MANAGER.code(),
            UserRole.DEVELOPER.code());

    /**
     * Legitimately formatted hash used when the username is unknown, so the verifier still
     * performs a full PBKDF2 derivation and both failure paths take comparable time.
     */
    private static final String UNKNOWN_USER_HASH = new PasswordHasher().hash("dummy");

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final JwtTokenService tokenService;

    public AuthService(UserRepository userRepository, PasswordHasher passwordHasher, JwtTokenService tokenService) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher must not be null");
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService must not be null");
    }

    /**
     * Verifies credentials and issues a token for the matching user.
     *
     * @param username login name
     * @param password plaintext password attempt
     * @return token plus the signed-in user view
     */
    public AuthResult login(String username, String password) {
        String trimmedUsername = username == null ? "" : username.trim();
        User user = userRepository.findByUsername(trimmedUsername).orElse(null);
        // Always run the verifier exactly once—against the stored hash for known users and a
        // dummy hash otherwise—so unknown users and wrong passwords stay indistinguishable
        // by response timing.
        String passwordAttempt = password == null ? "" : password;
        String storedHash = user == null ? UNKNOWN_USER_HASH : user.passwordHash();
        boolean matches = passwordHasher.verify(passwordAttempt, storedHash) && user != null;
        if (!matches) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIAL, INVALID_CREDENTIAL_MESSAGE);
        }
        return issue(user);
    }

    /**
     * Registers a fresh user and signs it in immediately.
     *
     * @param username    desired unique login name
     * @param password    plaintext password to hash
     * @param displayName optional display name
     * @param role        optional role limited to the self-registerable whitelist, defaults to DEVELOPER
     * @return token plus the registered user view
     */
    public AuthResult register(String username, String password, String displayName, String role) {
        String trimmedUsername = username == null ? "" : username.trim();
        if (trimmedUsername.isEmpty() || trimmedUsername.length() > MAX_USERNAME_LENGTH) {
            throw new IllegalArgumentException("username must be 1-" + MAX_USERNAME_LENGTH + " characters");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "password must be " + MIN_PASSWORD_LENGTH + "-" + MAX_PASSWORD_LENGTH + " characters");
        }
        String trimmedDisplayName = displayName == null || displayName.isBlank() ? null : displayName.trim();
        if (trimmedDisplayName != null && trimmedDisplayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException("displayName must be at most " + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
        String effectiveRole = resolveRegisterableRole(role);
        if (userRepository.findByUsername(trimmedUsername).isPresent()) {
            throw new AuthException(AuthErrorCode.USERNAME_TAKEN, "用户名已被占用");
        }
        User user = User.newUser(trimmedUsername, passwordHasher.hash(password), trimmedDisplayName, effectiveRole);
        return issue(userRepository.save(user));
    }

    /**
     * [AIREVIEW-PLAN-027] Validates the optional registration role against the self-registerable
     * whitelist; {@code ADMIN} and any other value outside the whitelist fail the shared 400
     * contract, and an absent role defaults to {@code DEVELOPER}.
     */
    private String resolveRegisterableRole(String role) {
        if (role == null || role.isBlank()) {
            return UserRole.DEVELOPER.code();
        }
        String trimmed = role.trim();
        if (!REGISTERABLE_ROLES.contains(trimmed)) {
            throw new IllegalArgumentException(
                    "role must be one of " + String.join(", ", REGISTERABLE_ROLES));
        }
        return trimmed;
    }

    private AuthResult issue(User user) {
        IssuedToken issuedToken = tokenService.issue(user);
        return new AuthResult(
                issuedToken.token(),
                issuedToken.expiresAt(),
                new UserView(user.username(), user.displayName(), user.role()));
    }

    /**
     * Successful authentication payload shared by login and register.
     *
     * @author wangli
     */
    public record AuthResult(String token, Instant expiresAt, UserView user) {
    }

    /**
     * Safe user projection for API responses (never exposes the password hash).
     *
     * @author wangli
     */
    public record UserView(String username, String displayName, String role) {
    }
}
