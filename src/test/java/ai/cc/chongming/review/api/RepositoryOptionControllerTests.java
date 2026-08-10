package ai.cc.chongming.review.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.cc.chongming.review.config.RepositoryAccessProperties;
import ai.cc.chongming.review.config.RepositoryAccessProperties.RepositoryDefinition;

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
                new RepositoryDefinition("cx-ai", "E:/aicode/cx-ai", "CX AI"),
                new RepositoryDefinition("chongming", "E:/aicode/chongming", "重明")));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RepositoryOptionController(properties)).build();

        mockMvc.perform(get("/api/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("cx-ai"))
                .andExpect(jsonPath("$[0].displayName").value("CX AI"))
                .andExpect(jsonPath("$[0].root").doesNotExist())
                .andExpect(jsonPath("$[1].id").value("chongming"))
                .andExpect(jsonPath("$[1].displayName").value("重明"));
    }

    @Test
    void fallsBackToRepositoryIdWhenDisplayNameIsMissing() throws Exception {
        RepositoryAccessProperties properties = new RepositoryAccessProperties(List.of(
                new RepositoryDefinition("cx-ai", "E:/aicode/cx-ai", null)));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RepositoryOptionController(properties)).build();

        mockMvc.perform(get("/api/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("cx-ai"));
    }

    @Test
    void returnsAnEmptyArrayWhenNoRepositoryIsConfigured() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new RepositoryOptionController(new RepositoryAccessProperties(null)))
                .build();

        mockMvc.perform(get("/api/repositories"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void rejectsDuplicateRepositoryIdsAtConfigurationBoundary() {
        assertThatThrownBy(() -> new RepositoryAccessProperties(List.of(
                new RepositoryDefinition("cx-ai", "E:/aicode/cx-ai", "CX AI"),
                new RepositoryDefinition("cx-ai", "E:/aicode/cx-ai-copy", "CX AI Copy"))))
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
}
