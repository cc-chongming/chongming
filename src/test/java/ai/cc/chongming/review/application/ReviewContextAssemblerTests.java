package ai.cc.chongming.review.application;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cc.chongming.review.application.ReviewContextAssembler.ContextFact;
import ai.cc.chongming.review.application.ReviewContextAssembler.ContextRequest;
import ai.cc.chongming.review.application.ReviewContextAssembler.Priority;
import ai.cc.chongming.review.domain.gateway.StructuredOutputs.Kind;
import ai.cc.chongming.review.domain.model.ContextScoutConclusion;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.repository.ContextScoutConclusionStore;
import ai.cc.chongming.review.domain.role.RolePack;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-023#5] Tests deterministic public-context selection and persisted Scout reload.
 *
 * @author zyj
 */
class ReviewContextAssemblerTests {

    @Test
    void reloadsTheCurrentAttemptScoutConclusionAsAPublicRoleFact() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ContextScoutConclusion conclusion = new ContextScoutConclusion(
                reviewId,
                2,
                1,
                "已定位评审查询入口",
                List.of("src/main"),
                List.of("ReviewQueryService"),
                List.of("不得泄露宿主路径"),
                List.of("历史尝试可能只有公开摘要"),
                List.of("src/main/java/ai/cc/chongming/review/application/ReviewQueryService.java"),
                Map.of("BACKEND", List.of("src/main/")),
                "{}",
                Instant.parse("2026-08-10T08:00:00Z"));
        ContextScoutConclusionStore store = new ContextScoutConclusionStore() {
            @Override
            public void save(ContextScoutConclusion ignored) {
            }

            @Override
            public Optional<ContextScoutConclusion> find(ReviewId id, int attemptNo) {
                return reviewId.equals(id) && attemptNo == 2 ? Optional.of(conclusion) : Optional.empty();
            }
        };
        ReviewContextAssembler assembler = new ReviewContextAssembler(store);

        ContextFact fact = assembler.contextScoutFact(reviewId, 2, RoleType.BACKEND).orElseThrow();

        assertThat(fact.selector()).isEqualTo("scout-overview");
        assertThat(fact.publicText())
                .contains("已定位评审查询入口")
                .contains("ReviewQueryService")
                .contains("不得泄露宿主路径")
                .contains("历史尝试可能只有公开摘要")
                .contains("src/main/");
    }

    @Test
    void keepsCriticalAndAllowedFactsWhenTheContextBudgetIsExceeded() {
        RolePack rolePack = new RolePack(
                RoleType.BACKEND,
                "Backend reviewer",
                List.of("Always"),
                "backend-v1",
                Set.of("requirement-snapshot", "public-claims"),
                List.of("Check API"),
                Set.of("searchText"),
                Kind.ROLE_ASSESSMENT,
                "role-reviewer",
                Duration.ofSeconds(30),
                4);
        ReviewContextAssembler assembler = new ReviewContextAssembler();
        var result = assembler.assemble(new ContextRequest(
                new ReviewId(UUID.randomUUID()),
                rolePack,
                List.of(
                        fact("hidden", "private-history", Priority.CRITICAL, false, "must not leak"),
                        fact("normal", "requirement-snapshot", Priority.NORMAL, false, "ordinary context"),
                        fact("critical", "public-claims", Priority.CRITICAL, true, "critical claim")),
                15));

        assertThat(result.facts()).extracting(ContextFact::factId).containsExactly("critical");
        assertThat(result.truncated()).isTrue();
        assertThat(result.characterCount()).isEqualTo("critical claim".length());
    }

    private ContextFact fact(String id, String selector, Priority priority, boolean disputed, String text) {
        return new ContextFact(id, selector, priority, disputed, Instant.parse("2026-07-16T00:00:00Z"), text);
    }
}
