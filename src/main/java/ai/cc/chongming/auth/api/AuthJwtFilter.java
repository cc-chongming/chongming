package ai.cc.chongming.auth.api;

import ai.cc.chongming.auth.application.JwtTokenService;
import ai.cc.chongming.auth.application.JwtTokenService.AuthPrincipal;
import ai.cc.chongming.auth.domain.AuthErrorCode;
import ai.cc.chongming.auth.domain.AuthException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards {@code /api/**} with bearer-token validation. Only the credential endpoints
 * ({@code /api/auth/login} and {@code /api/auth/register}) plus anything outside {@code /api/}
 * pass through untouched; {@code /api/auth/me} stays protected because it needs the verified
 * principal. Credentials are read from the {@code Authorization: Bearer} header first, then
 * from the {@code access_token} query parameter that browser SSE connections rely on. A
 * rejected request is short-circuited with the same ProblemDetail contract the exception
 * handler produces, including {@code code} and {@code x-trace-id}.
 *
 * @author wangli
 */
public class AuthJwtFilter extends OncePerRequestFilter {

    /** Request attribute carrying the verified principal for downstream handlers. */
    public static final String PRINCIPAL_ATTRIBUTE = "auth.principal";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ACCESS_TOKEN_PARAMETER = "access_token";
    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String REGISTER_PATH = "/api/auth/register";

    private final JwtTokenService tokenService;
    private final ObjectMapper objectMapper;

    public AuthJwtFilter(JwtTokenService tokenService, ObjectMapper objectMapper) {
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = resolvePath(request);
        if (!path.startsWith("/api/") || LOGIN_PATH.equals(path) || REGISTER_PATH.equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = resolveToken(request);
        if (token == null || token.isBlank()) {
            writeUnauthorized(response, "缺少 Bearer 令牌");
            return;
        }
        try {
            AuthPrincipal principal = tokenService.parse(token);
            request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        } catch (AuthException ex) {
            writeUnauthorized(response, ex.getMessage());
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Derives the application-relative path, staying correct under a non-root context path and
     * under MockMvc where {@code servletPath} may be empty.
     */
    private String resolvePath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isEmpty()) {
            return servletPath;
        }
        String requestUri = request.getRequestURI() == null ? "" : request.getRequestURI();
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        return contextPath.isEmpty() || !requestUri.startsWith(contextPath)
                ? requestUri
                : requestUri.substring(contextPath.length());
    }

    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        // RFC 9110 treats the scheme as case-insensitive, so accept "bearer"/"BEARER" too.
        if (authorization != null && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        return request.getParameter(ACCESS_TOKEN_PARAMETER);
    }

    private void writeUnauthorized(HttpServletResponse response, String detail) throws IOException {
        ResponseEntity<ProblemDetail> unauthorized =
                AuthExceptionHandler.unauthorizedResponse(AuthErrorCode.UNAUTHENTICATED, detail);
        ProblemDetail body = Objects.requireNonNull(unauthorized.getBody());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        unauthorized.getHeaders().forEach((name, values) -> values.forEach(value -> response.setHeader(name, value)));
        // Flatten the ProblemDetail extension properties (code, x-trace-id) into the top level,
        // matching what Spring MVC's RFC 7807 message conversion produces for advice responses.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", body.getType() == null ? "about:blank" : body.getType().toString());
        payload.put("title", body.getTitle());
        payload.put("status", body.getStatus());
        payload.put("detail", body.getDetail());
        payload.putAll(body.getProperties());
        objectMapper.writeValue(response.getWriter(), payload);
    }
}
