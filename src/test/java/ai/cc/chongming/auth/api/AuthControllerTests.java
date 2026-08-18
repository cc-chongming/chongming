package ai.cc.chongming.auth.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.cc.chongming.auth.application.AuthService;
import ai.cc.chongming.auth.application.AuthService.AuthResult;
import ai.cc.chongming.auth.application.AuthService.UserView;
import ai.cc.chongming.auth.application.JwtTokenService.AuthPrincipal;
import ai.cc.chongming.auth.domain.AuthErrorCode;
import ai.cc.chongming.auth.domain.AuthException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HTTP contract tests for authentication endpoints, following the review domain's standalone
 * MockMvc setup with an explicit controller advice.
 *
 * @author wangli
 */
class AuthControllerTests {

    private AuthService authService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new AuthExceptionHandler())
                .build();
    }

    @Test
    void loginReturnsTokenAndUserProfile() throws Exception {
        when(authService.login("alice", "password123")).thenReturn(new AuthResult(
                "signed-token",
                Instant.parse("2026-08-12T12:00:00Z"),
                new UserView("alice", "Alice", "USER")));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"password123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("signed-token"))
                .andExpect(jsonPath("$.expiresAt").value("2026-08-12T12:00:00Z"))
                .andExpect(jsonPath("$.user.username").value("alice"))
                .andExpect(jsonPath("$.user.displayName").value("Alice"))
                .andExpect(jsonPath("$.user.role").value("USER"));
    }

    @Test
    void loginWithWrongPasswordReturnsUnauthorizedWithStableCode() throws Exception {
        when(authService.login("alice", "wrong-password"))
                .thenThrow(new AuthException(AuthErrorCode.INVALID_CREDENTIAL, "用户名或密码错误"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIAL"))
                .andExpect(jsonPath("$.detail").value("用户名或密码错误"))
                .andExpect(header().exists("x-trace-id"));
    }

    @Test
    void registerWithTakenUsernameReturnsConflict() throws Exception {
        // [AIREVIEW-PLAN-027] Bodies without a role field pass null through to the service.
        when(authService.register("bob", "password123", "Bobby", null, null))
                .thenThrow(new AuthException(AuthErrorCode.USERNAME_TAKEN, "用户名已被占用"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob","password":"password123","displayName":"Bobby"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"))
                .andExpect(header().exists("x-trace-id"));
    }

    @Test
    void registerValidationFailureReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"","password":"password123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AUTH_REQUEST"));
    }

    /**
     * [AIREVIEW-PLAN-027] The register body carries an optional role; non-whitelisted values
     * (including ADMIN self-registration) surface the shared 400 contract.
     */
    @Test
    void registerWithAdminRoleReturnsBadRequest() throws Exception {
        when(authService.register("bob", "password123", "Bobby", "ADMIN", null))
                .thenThrow(new IllegalArgumentException("role must be one of PRODUCT_MANAGER, PROJECT_MANAGER, DEVELOPER"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob","password":"password123","displayName":"Bobby","role":"ADMIN"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AUTH_REQUEST"))
                .andExpect(jsonPath("$.detail").value("role must be one of PRODUCT_MANAGER, PROJECT_MANAGER, DEVELOPER"));
    }

    /**
     * [AIREVIEW-PLAN-027] Whitelisted roles are forwarded verbatim to the service.
     */
    @Test
    void registerWithWhitelistedRoleIssuesTokenWithThatRole() throws Exception {
        when(authService.register("bob", "password123", "Bobby", "PRODUCT_MANAGER", null)).thenReturn(new AuthResult(
                "signed-token",
                Instant.parse("2026-08-12T12:00:00Z"),
                new UserView("bob", "Bobby", "PRODUCT_MANAGER")));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob","password":"password123","displayName":"Bobby","role":"PRODUCT_MANAGER"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("signed-token"))
                .andExpect(jsonPath("$.user.role").value("PRODUCT_MANAGER"));
    }

    /**
     * [AIREVIEW-PLAN-025] The register body carries an optional company uid; it is forwarded to
     * the service and echoed on the issued user profile.
     */
    @Test
    void registerWithCompanyUidIssuesTokenCarryingTheUid() throws Exception {
        when(authService.register("bob", "password123", "Bobby", null, "corp-10086")).thenReturn(new AuthResult(
                "signed-token",
                Instant.parse("2026-08-12T12:00:00Z"),
                new UserView("bob", "Bobby", "DEVELOPER", "corp-10086")));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob","password":"password123","displayName":"Bobby","uid":"corp-10086"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("signed-token"))
                .andExpect(jsonPath("$.user.uid").value("corp-10086"));
    }

    /**
     * [AIREVIEW-PLAN-025] A company uid already bound to another account surfaces a stable 409.
     */
    @Test
    void registerWithTakenCompanyUidReturnsConflict() throws Exception {
        when(authService.register("bob", "password123", "Bobby", null, "corp-10086"))
                .thenThrow(new AuthException(AuthErrorCode.UID_TAKEN, "公司 UID 已被其他账号绑定"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob","password":"password123","displayName":"Bobby","uid":"corp-10086"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UID_TAKEN"))
                .andExpect(header().exists("x-trace-id"));
    }

    @Test
    void malformedJsonBodyReturnsBadRequestWithStableCode() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-valid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AUTH_REQUEST"))
                .andExpect(jsonPath("$.detail").value("request body is invalid"))
                .andExpect(header().exists("x-trace-id"));
    }

    @Test
    void meReturnsPrincipalStoredByJwtFilter() throws Exception {
        AuthController controller = new AuthController(authService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("alice", "Alice", "USER"));

        AuthController.UserResponse response = controller.me(request);

        org.assertj.core.api.Assertions.assertThat(response.username()).isEqualTo("alice");
        org.assertj.core.api.Assertions.assertThat(response.role()).isEqualTo("USER");
    }

    @Test
    void meWithoutPrincipalReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
