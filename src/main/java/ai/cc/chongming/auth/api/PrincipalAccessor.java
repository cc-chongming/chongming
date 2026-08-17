package ai.cc.chongming.auth.api;

import ai.cc.chongming.auth.application.JwtTokenService.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.Optional;

/**
 * [AIREVIEW-PLAN-027] Shared read access to the verified principal that {@link AuthJwtFilter}
 * stores on the request. Returns {@link Optional#empty()} when no principal is present so
 * callers on demo/test profiles without the authentication module can keep their open
 * behaviour, while protected endpoints can turn the empty value into their own stable error.
 *
 * @author wangli
 */
public final class PrincipalAccessor {

    /**
     * Reads the principal attribute written by {@link AuthJwtFilter}.
     *
     * @param request current servlet request
     * @return verified principal, empty when the request carries none (demo/test profiles)
     */
    public Optional<AuthPrincipal> requirePrincipal(HttpServletRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Object principal = request.getAttribute(AuthJwtFilter.PRINCIPAL_ATTRIBUTE);
        return principal instanceof AuthPrincipal authPrincipal ? Optional.of(authPrincipal) : Optional.empty();
    }
}
