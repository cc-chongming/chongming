package ai.cc.chongming.auth.api;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.cc.chongming.auth.application.AuthService;
import ai.cc.chongming.auth.application.JwtTokenService.AuthPrincipal;
import ai.cc.chongming.auth.domain.User;
import ai.cc.chongming.auth.infrastructure.InMemoryUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HTTP contract tests for the administrator-only user directory endpoint, kept separate from
 * {@link AuthControllerTests} so the credential-flow tests stay untouched.
 *
 * @author wangli
 */
class UserDirectoryEndpointTests {

    private InMemoryUserRepository userRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userRepository = new InMemoryUserRepository();
        userRepository.save(User.newUser("admin", "PBKDF2$210000$c2FsdA==$aGFzaA==", "管理员", "ADMIN"));
        userRepository.save(User.newUser("bob", "PBKDF2$210000$c2FsdA==$aGFzaA==", "Bob", "USER"));
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(mock(AuthService.class), userRepository))
                .setControllerAdvice(new AuthExceptionHandler())
                .build();
    }

    @Test
    void adminListsAllUsersWithoutCredentialMaterial() throws Exception {
        mockMvc.perform(get("/api/users")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("admin", "管理员", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("admin"))
                .andExpect(jsonPath("$[0].role").value("ADMIN"))
                .andExpect(jsonPath("$[1].username").value("bob"))
                .andExpect(jsonPath("$[1].displayName").value("Bob"));
    }

    @Test
    void regularUserIsForbiddenWithStableCode() throws Exception {
        mockMvc.perform(get("/api/users")
                        .requestAttr(AuthJwtFilter.PRINCIPAL_ATTRIBUTE, new AuthPrincipal("bob", "Bob", "USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(header().exists("x-trace-id"));
    }

    @Test
    void missingPrincipalIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
