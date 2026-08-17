package ai.cc.chongming.auth.api;

import ai.cc.chongming.auth.application.AuthService;
import ai.cc.chongming.auth.application.AuthService.AuthResult;
import ai.cc.chongming.auth.application.JwtTokenService.AuthPrincipal;
import ai.cc.chongming.auth.config.AuthModuleEnabledCondition;
import ai.cc.chongming.auth.domain.AuthErrorCode;
import ai.cc.chongming.auth.domain.AuthException;
import ai.cc.chongming.auth.domain.UserRole;
import ai.cc.chongming.auth.domain.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints: credential login, self-service registration, current-user lookup
 * and the administrator-only user directory. Successful login and registration share the
 * {@code {token, expiresAt, user}} envelope; the {@code /me} endpoint returns the principal
 * that {@link AuthJwtFilter} already validated.
 *
 * @author wangli
 */
@RestController
@Conditional(AuthModuleEnabledCondition.class)
public class AuthController {

    /** Request attribute key where {@link AuthJwtFilter} stores the verified principal. */
    public static final String PRINCIPAL_ATTRIBUTE = AuthJwtFilter.PRINCIPAL_ATTRIBUTE;

    private final AuthService authService;
    private final UserRepository userRepository;
    private final PrincipalAccessor principalAccessor = new PrincipalAccessor();

    public AuthController(AuthService authService) {
        this(authService, null);
    }

    @Autowired
    public AuthController(AuthService authService, UserRepository userRepository) {
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.userRepository = userRepository;
    }

    @PostMapping("/api/auth/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        AuthResult result = authService.login(request.username(), request.password());
        return AuthResponse.from(result);
    }

    @PostMapping("/api/auth/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        AuthResult result = authService.register(
                request.username(), request.password(), request.displayName(), request.role());
        return AuthResponse.from(result);
    }

    /**
     * Returns the caller's profile from the bearer token validated by the JWT filter.
     *
     * @param request current servlet request carrying the principal attribute
     * @return current user view
     */
    @GetMapping("/api/auth/me")
    public UserResponse me(HttpServletRequest request) {
        AuthPrincipal authPrincipal = requirePrincipal(request);
        return new UserResponse(authPrincipal.username(), authPrincipal.displayName(), authPrincipal.role());
    }

    /**
     * Administrator-only user directory used by task dispatch. The projection never carries
     * credential material.
     *
     * @param request current servlet request carrying the principal attribute
     * @return credential-free view of every account
     */
    @GetMapping("/api/users")
    public List<UserResponse> users(HttpServletRequest request) {
        AuthPrincipal authPrincipal = requirePrincipal(request);
        // [AIREVIEW-PLAN-027] Legacy USER or unknown roles parse to developer-level semantics
        // and stay outside the administrator-only directory.
        if (!UserRole.parse(authPrincipal.role()).viewsAllRequirements()) {
            throw new AuthException(AuthErrorCode.FORBIDDEN, "仅管理员可查看用户列表");
        }
        if (userRepository == null) {
            throw new AuthException(AuthErrorCode.FORBIDDEN, "用户目录当前不可用");
        }
        return userRepository.findAll().stream()
                .map(user -> new UserResponse(user.username(), user.displayName(), user.role()))
                .toList();
    }

    private AuthPrincipal requirePrincipal(HttpServletRequest request) {
        // [AIREVIEW-PLAN-027] Shared attribute read lives in PrincipalAccessor; this endpoint
        // keeps its historical 401 contract for missing principals.
        return principalAccessor.requirePrincipal(request)
                .orElseThrow(() -> new AuthException(AuthErrorCode.UNAUTHENTICATED, "当前请求未携带有效的认证凭据"));
    }

    /**
     * Login request body.
     *
     * @author wangli
     */
    public record LoginRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 128) String password) {
    }

    /**
     * Registration request body. {@code role} is optional; [AIREVIEW-PLAN-027] restricts it to
     * the self-registerable role set and defaults to {@code DEVELOPER}.
     *
     * @author wangli
     */
    public record RegisterRequest(
            @NotBlank @Size(min = 1, max = 64) String username,
            @NotBlank @Size(min = 8, max = 128) String password,
            @Size(max = 64) String displayName,
            @Size(max = 32) String role) {
    }

    /**
     * Shared success envelope for login and register.
     *
     * @author wangli
     */
    public record AuthResponse(String token, Instant expiresAt, UserResponse user) {

        static AuthResponse from(AuthResult result) {
            return new AuthResponse(
                    result.token(),
                    result.expiresAt(),
                    new UserResponse(result.user().username(), result.user().displayName(), result.user().role()));
        }
    }

    /**
     * Public user projection.
     *
     * @author wangli
     */
    public record UserResponse(String username, String displayName, String role) {
    }
}
