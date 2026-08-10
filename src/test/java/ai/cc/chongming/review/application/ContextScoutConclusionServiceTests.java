package ai.cc.chongming.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ai.cc.chongming.review.domain.model.ContextScoutConclusion;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.ContextScoutConclusionStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * [AIREVIEW-PLAN-023#5] Verifies that public Scout output is persisted as a readable conclusion.
 *
 * @author zyj
 */
class ContextScoutConclusionServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-10T08:00:00Z");

    @Test
    void parsesAndPersistsTheStructuredPublicResult() {
        ContextScoutConclusionStore store = mock(ContextScoutConclusionStore.class);
        ContextScoutConclusionService service = new ContextScoutConclusionService(
                store, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        String result = """
                ```json
                {
                  "summary": "评审入口集中在 review 模块。",
                  "moduleRoots": ["src/main", "frontend"],
                  "entryPoints": ["ReviewCommandController", "ReviewLiveView.vue"],
                  "constraints": ["只读冻结快照"],
                  "risks": ["旧评审缺少结构化结论"],
                  "evidencePaths": ["src/main/java/ReviewCommandController.java"],
                  "roleScopes": {"BACKEND": ["src/main/"], "FRONTEND": ["frontend/"]}
                }
                ```
                """;

        ContextScoutConclusion conclusion = service.capture(reviewId, 2, result);

        assertThat(conclusion.reviewId()).isEqualTo(reviewId);
        assertThat(conclusion.attemptNo()).isEqualTo(2);
        assertThat(conclusion.schemaVersion()).isEqualTo(1);
        assertThat(conclusion.summary()).isEqualTo("评审入口集中在 review 模块。");
        assertThat(conclusion.moduleRoots()).containsExactly("src/main", "frontend");
        assertThat(conclusion.entryPoints()).containsExactly("ReviewCommandController", "ReviewLiveView.vue");
        assertThat(conclusion.constraints()).containsExactly("只读冻结快照");
        assertThat(conclusion.risks()).containsExactly("旧评审缺少结构化结论");
        assertThat(conclusion.evidencePaths()).containsExactly("src/main/java/ReviewCommandController.java");
        assertThat(conclusion.roleScopes()).containsEntry("BACKEND", java.util.List.of("src/main/"));
        assertThat(conclusion.rawPublicResult()).isEqualTo(result);
        assertThat(conclusion.createdAt()).isEqualTo(NOW);
        verify(store).save(conclusion);
    }

    @Test
    void preservesMalformedPublicOutputAsAReadableFallback() {
        ContextScoutConclusionStore store = mock(ContextScoutConclusionStore.class);
        ContextScoutConclusionService service = new ContextScoutConclusionService(
                store, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        String result = "已收集后端入口与前端页面，但模型未返回 JSON。";

        ContextScoutConclusion conclusion = service.capture(reviewId, 1, result);

        assertThat(conclusion.summary()).isEqualTo(result);
        assertThat(conclusion.moduleRoots()).isEmpty();
        assertThat(conclusion.entryPoints()).isEmpty();
        assertThat(conclusion.constraints()).isEmpty();
        assertThat(conclusion.risks()).isEmpty();
        assertThat(conclusion.evidencePaths()).isEmpty();
        assertThat(conclusion.roleScopes()).isEmpty();
        ArgumentCaptor<ContextScoutConclusion> captor = ArgumentCaptor.forClass(ContextScoutConclusion.class);
        verify(store).save(captor.capture());
        assertThat(captor.getValue()).isEqualTo(conclusion);
    }
}
