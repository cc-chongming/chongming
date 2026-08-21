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
        return register(username, password, displayName, role, null);
    }

    /**
     * [AIREVIEW-PLAN-025] Registration carrying the optional company-internal uid used later as
     * the message-binding identity. A non-blank uid must not be bound to another account.
     *
     * @param companyUid optional company-internal user identifier, unique when present
     * @return token plus the registered user view
     */
    public AuthResult register(String username, String password, String displayName, String role, String companyUid) {
        return register(username, password, displayName, role, companyUid, null);
    }

    /**
     * [AIREVIEW-PLAN-030] Registration carrying the optional mail destination used later by the
     * notification matrix.
     *
     * @param email optional mail destination
     * @return token plus the registered user view
     */
    public AuthResult register(
            String username, String password, String displayName, String role, String companyUid,
            String email) {
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
        String trimmedCompanyUid = companyUid == null || companyUid.isBlank() ? null : companyUid.trim();
        if (userRepository.findByUsername(trimmedUsername).isPresent()) {
            throw new AuthException(AuthErrorCode.USERNAME_TAKEN, "用户名已被占用");
        }
        // [AIREVIEW-PLAN-025] Company uids are unique across accounts when present; the database
        // unique index is the second line of defence for concurrent registrations.
        if (trimmedCompanyUid != null && userRepository.findByCompanyUid(trimmedCompanyUid).isPresent()) {
            throw new AuthException(AuthErrorCode.UID_TAKEN, "公司 UID 已被其他账号绑定");
        }
        User user = User.newUser(
                trimmedUsername, passwordHasher.hash(password), trimmedDisplayName, effectiveRole, trimmedCompanyUid)
                .withContacts(email);
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
                new UserView(user.username(), user.displayName(), user.role(), user.companyUid(),
                        user.email()));
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
     * [AIREVIEW-PLAN-025] Carries the optional company uid for message binding.
     *
     * @author wangli
     */
    public record UserView(String username, String displayName, String role, String companyUid,
                           String email) {

        /** Legacy projection without the company uid. */
        public UserView(String username, String displayName, String role) {
            this(username, displayName, role, null, null);
        }

        /** [AIREVIEW-PLAN-025] Legacy projection without the mail destination. */
        public UserView(String username, String displayName, String role, String companyUid) {
            this(username, displayName, role, companyUid, null);
        }
    }
}
