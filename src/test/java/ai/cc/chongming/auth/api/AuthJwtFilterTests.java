package ai.cc.chongming.auth.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.cc.chongming.auth.application.JwtTokenService;
import ai.cc.chongming.auth.application.JwtTokenService.IssuedToken;
import ai.cc.chongming.auth.config.AuthProperties;
import ai.cc.chongming.auth.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verifies the JWT filter's path scoping and credential resolution using MockMvc filters.
 *
 * @author wangli
 */
class AuthJwtFilterTests {

    private JwtTokenService tokenService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        tokenService = new JwtTokenService(
                new AuthProperties(true, "chongming-test-jwt-secret-0123456789abcdef", Duration.ofHours(1)));
        AuthJwtFilter filter = new AuthJwtFilter(tokenService, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(filter)
                .build();
    }

    private String validToken() {
        IssuedToken issued = tokenService.issue(
                new User(1L, "alice", "PBKDF2$210000$salt$hash", "Alice", "USER"));
        return issued.token();
    }

    @Test
    void allowsCredentialEndpointsWithoutToken() throws Exception {
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().isOk())
                .andExpect(content().string("open"));
        mockMvc.perform(get("/api/auth/register"))
                .andExpect(status().isOk())
                .andExpect(content().string("open"));
    }

    @Test
    void meEndpointStaysProtectedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void meEndpointReceivesPrincipalWithValidToken() throws Exception {
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isOk())
                .andExpect(content().string("principal=alice"));
    }

    @Test
    void allowsNonApiPathsWithoutToken() throws Exception {
        mockMvc.perform(get("/public/page"))
                .andExpect(status().isOk())
                .andExpect(content().string("public"));
    }

    @Test
    void rejectsProtectedApiRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/api/dashboard"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.x-trace-id").exists())
                .andExpect(header().exists("x-trace-id"));
    }

    @Test
    void rejectsProtectedApiRequestWithInvalidToken() throws Exception {
        mockMvc.perform(get("/api/dashboard").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void allowsProtectedRequestWithValidBearerHeader() throws Exception {
        mockMvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isOk())
                .andExpect(content().string("principal=alice"));
    }

    @Test
    void acceptsLowercaseBearerSchemePerRfc9110() throws Exception {
        mockMvc.perform(get("/api/dashboard").header("Authorization", "bearer " + validToken()))
                .andExpect(status().isOk())
                .andExpect(content().string("principal=alice"));
    }

    @Test
    void allowsProtectedRequestWithAccessTokenQueryParameterForSse() throws Exception {
        mockMvc.perform(get("/api/dashboard").param("access_token", validToken()))
                .andExpect(status().isOk())
                .andExpect(content().string("principal=alice"));
    }

    /**
     * Probe endpoints standing in for real protected and public routes.
     *
     * @author wangli
     */
    @RestController
    static class ProbeController {

        @GetMapping("/api/auth/login")
        public String login() {
            return "open";
        }

        @GetMapping("/api/auth/register")
        public String register() {
            return "open";
        }

        @GetMapping("/api/auth/me")
        public String me(HttpServletRequest request) {
            return principalName(request);
        }

        @GetMapping("/public/page")
        public String publicPage() {
            return "public";
        }

        @GetMapping("/api/dashboard")
        public String dashboard(HttpServletRequest request) {
            return principalName(request);
        }

        private String principalName(HttpServletRequest request) {
            Object principal = request.getAttribute(AuthJwtFilter.PRINCIPAL_ATTRIBUTE);
            if (principal instanceof JwtTokenService.AuthPrincipal authPrincipal) {
                return "principal=" + authPrincipal.username();
            }
            return "principal=none";
        }
    }
}
