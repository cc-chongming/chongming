package ai.cc.chongming.review.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.cc.chongming.review.config.RepositoryAccessProperties;
import ai.cc.chongming.review.config.RepositoryAccessProperties.RepositoryDefinition;
import ai.cc.chongming.review.config.RepositoryAccessProperties.RepositoryDefinition.Remote;
import ai.cc.chongming.review.config.RepositoryAccessProperties.RepositoryDefinition.Remote.Auth;
import ai.cc.chongming.review.config.RepositoryAccessProperties.RepositoryDefinition.Remote.Auth.AuthType;
import ai.cc.chongming.review.config.RepositoryAccessProperties.RepositoryDefinition.RepositoryType;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * [AIREVIEW-PLAN-023#2] Verifies the safe, ordered repository option contract.
 *
 * @author zyj
 */
class RepositoryOptionControllerTests {

    @Test
    void returnsConfiguredRepositoriesInOrderWithoutPhysicalRoots() throws Exception {
        RepositoryAccessProperties properties = new RepositoryAccessProperties(List.of(
                new RepositoryDefinition("cx-ai", "E:/aicode/cx-ai", "CX AI", null, null),
                new RepositoryDefinition("chongming", "E:/aicode/chongming", "重明", null, null)), null, null);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RepositoryOptionController(properties)).build();

        mockMvc.perform(get("/api/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("cx-ai"))
                .andExpect(jsonPath("$[0].displayName").value("CX AI"))
                .andExpect(jsonPath("$[0].type").value("local"))
                .andExpect(jsonPath("$[0].root").doesNotExist())
                .andExpect(jsonPath("$[1].id").value("chongming"))
                .andExpect(jsonPath("$[1].displayName").value("重明"));
    }

    /** [AIREVIEW-PLAN-028] Remote entries keep the opaque id contract and expose their type. */
    @Test
    void labelsRemoteRepositoriesWithoutExposingTheirUrl() throws Exception {
        RepositoryAccessProperties properties = new RepositoryAccessProperties(List.of(
                new RepositoryDefinition(
                        "demo-remote", null, "演示远程仓库", RepositoryType.REMOTE,
                        new Remote("https://example.com/demo.git", "main",
                                new Auth(AuthType.NONE, null, null), null))), null, null);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RepositoryOptionController(properties)).build();

        mockMvc.perform(get("/api/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("demo-remote"))
                .andExpect(jsonPath("$[0].displayName").value("演示远程仓库"))
                .andExpect(jsonPath("$[0].type").value("remote"))
                .andExpect(jsonPath("$[0].url").doesNotExist());
    }

    @Test
    void fallsBackToRepositoryIdWhenDisplayNameIsMissing() throws Exception {
        RepositoryAccessProperties properties = new RepositoryAccessProperties(List.of(
                new RepositoryDefinition("cx-ai", "E:/aicode/cx-ai", null, null, null)), null, null);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RepositoryOptionController(properties)).build();

        mockMvc.perform(get("/api/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("cx-ai"));
    }

    @Test
    void returnsAnEmptyArrayWhenNoRepositoryIsConfigured() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new RepositoryOptionController(new RepositoryAccessProperties(null, null, null)))
                .build();

        mockMvc.perform(get("/api/repositories"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void rejectsDuplicateRepositoryIdsAtConfigurationBoundary() {
        assertThatThrownBy(() -> new RepositoryAccessProperties(List.of(
                new RepositoryDefinition("cx-ai", "E:/aicode/cx-ai", "CX AI", null, null),
                new RepositoryDefinition("cx-ai", "E:/aicode/cx-ai-copy", "CX AI Copy", null, null)), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate repository id")
                .hasMessageContaining("cx-ai");
    }

    @Test
    void bindsTheLocalRepositoryDefinitionThroughTheCanonicalRecordConstructor() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "review.repositories.allowed[0].id", "chongming",
                "review.repositories.allowed[0].root", "E:/aicode/chongming",
                "review.repositories.allowed[0].display-name", "重明"));

        RepositoryAccessProperties properties = new Binder(source)
                .bind("review.repositories", Bindable.of(RepositoryAccessProperties.class))
                .get();

        assertThat(properties.allowed()).singleElement().satisfies(repository -> {
            assertThat(repository.id()).isEqualTo("chongming");
            assertThat(repository.root()).isEqualTo("E:/aicode/chongming");
            assertThat(repository.displayName()).isEqualTo("重明");
        });
    }

    /** [AIREVIEW-PLAN-028] Remote definitions bind through the same canonical constructor chain. */
    @Test
    void bindsTheRemoteRepositoryDefinitionWithAuthAndTimeout() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "review.repositories.allow-internal", "true",
                "review.repositories.allowed[0].id", "demo-remote",
                "review.repositories.allowed[0].type", "remote",
                "review.repositories.allowed[0].display-name", "演示远程仓库",
                "review.repositories.allowed[0].remote.url", "https://example.com/demo.git",
                "review.repositories.allowed[0].remote.ref", "main",
                "review.repositories.allowed[0].remote.clone-timeout", "PT5M",
                "review.repositories.allowed[0].remote.auth.type", "https-token",
                "review.repositories.allowed[0].remote.auth.token-env", "DEMO_REMOTE_REPOSITORY_TOKEN"));

        RepositoryAccessProperties properties = new Binder(source)
                .bind("review.repositories", Bindable.of(RepositoryAccessProperties.class))
                .get();

        assertThat(properties.allowInternal()).isTrue();
        assertThat(properties.allowed()).singleElement().satisfies(repository -> {
            assertThat(repository.type()).isEqualTo(RepositoryType.REMOTE);
            assertThat(repository.root()).isNull();
            assertThat(repository.remote().url()).isEqualTo("https://example.com/demo.git");
            assertThat(repository.remote().ref()).isEqualTo("main");
            assertThat(repository.remote().cloneTimeout()).isEqualTo(Duration.ofMinutes(5));
            assertThat(repository.remote().auth().type()).isEqualTo(AuthType.HTTPS_TOKEN);
            assertThat(repository.remote().auth().tokenEnv()).isEqualTo("DEMO_REMOTE_REPOSITORY_TOKEN");
        });
    }

    /** [AIREVIEW-PLAN-028] A remote entry without its remote block fails at the configuration boundary. */
    @Test
    void rejectsRemoteRepositoriesWithoutARemoteBlock() {
        assertThatThrownBy(() -> new RepositoryDefinition(
                "demo-remote", null, "演示远程仓库", RepositoryType.REMOTE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remote block is required");
    }

    /** [AIREVIEW-PLAN-028] https-token auth must reference a token environment variable. */
    @Test
    void rejectsHttpsTokenAuthWithoutATokenEnvironmentVariable() {
        assertThatThrownBy(() -> new Auth(AuthType.HTTPS_TOKEN, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token-env is required");
    }
}
