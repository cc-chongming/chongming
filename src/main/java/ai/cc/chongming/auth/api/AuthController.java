package ai.cc.chongming.auth.api;

import ai.cc.chongming.auth.application.AuthService;
import ai.cc.chongming.auth.application.AuthService.AuthResult;
import ai.cc.chongming.auth.application.JwtTokenService.AuthPrincipal;
import ai.cc.chongming.auth.config.AuthModuleEnabledCondition;
import ai.cc.chongming.auth.domain.AuthErrorCode;
import ai.cc.chongming.auth.domain.AuthException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Objects;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints: credential login, self-service registration and current-user lookup.
 * Successful login and registration share the {@code {token, expiresAt, user}} envelope; the
 * {@code /me} endpoint returns the principal that {@link AuthJwtFilter} already validated.
 *
 * @author wangli
 */
@RestController
@RequestMapping("/api/auth")
@Conditional(AuthModuleEnabledCondition.class)
public class AuthController {

    /** Request attribute key where {@link AuthJwtFilter} stores the verified principal. */
    public static final String PRINCIPAL_ATTRIBUTE = AuthJwtFilter.PRINCIPAL_ATTRIBUTE;

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        AuthResult result = authService.login(request.username(), request.password());
        return AuthResponse.from(result);
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        AuthResult result = authService.register(request.username(), request.password(), request.displayName());
        return AuthResponse.from(result);
    }

    /**
     * Returns the caller's profile from the bearer token validated by the JWT filter.
     *
     * @param request current servlet request carrying the principal attribute
     * @return current user view
     */
    @GetMapping("/me")
    public UserResponse me(HttpServletRequest request) {
        Object principal = request.getAttribute(PRINCIPAL_ATTRIBUTE);
        if (!(principal instanceof AuthPrincipal authPrincipal)) {
            throw new AuthException(AuthErrorCode.UNAUTHENTICATED, "当前请求未携带有效的认证凭据");
        }
        return new UserResponse(authPrincipal.username(), authPrincipal.displayName(), authPrincipal.role());
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
     * Registration request body.
     *
     * @author wangli
     */
    public record RegisterRequest(
            @NotBlank @Size(min = 1, max = 64) String username,
            @NotBlank @Size(min = 8, max = 128) String password,
            @Size(max = 64) String displayName) {
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
