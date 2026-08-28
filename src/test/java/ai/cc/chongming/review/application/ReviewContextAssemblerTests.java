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
import ai.cc.chongming.review.domain.role.RolePack.Checkpoint;
import ai.cc.chongming.review.infrastructure.agentscope.tool.RepositoryFileGrantSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
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
    void hidesScoutEvidencePathsOutsideTheRoleScopedAuthorization() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ContextScoutConclusion conclusion = new ContextScoutConclusion(
                reviewId,
                1,
                1,
                "overview",
                List.of("src/main"),
                List.of("ReviewQueryService"),
                List.of("constraint"),
                List.of("risk"),
                List.of("src/main/java/App.java", "frontend/app.js"),
                Map.of("BACKEND", List.of("src/main/")),
                "{}",
                Instant.parse("2026-08-10T08:00:00Z"));
        ReviewContextAssembler assembler = new ReviewContextAssembler(storeOf(reviewId, conclusion));

        ContextFact backendFact = assembler.contextScoutFact(reviewId, 1, RoleType.BACKEND).orElseThrow();
        assertThat(backendFact.publicText())
                .contains("src/main/java/App.java")
                .doesNotContain("frontend/app.js");

        ContextFact frontendFact = assembler.contextScoutFact(reviewId, 1, RoleType.FRONTEND).orElseThrow();
        assertThat(frontendFact.publicText())
                .doesNotContain("frontend/app.js")
                .doesNotContain("src/main/java/App.java");
    }

    @Test
    void computesEffectiveReadableFilesAsAOneShotSetIntersection() {
        ReviewContextAssembler assembler = new ReviewContextAssembler();
        List<String> snapshotFiles = List.of(
                "docs/spec.md", "src/main/java/App.java", "frontend/app.js", "README.md");
        Predicate<String> backendPolicy = path -> path.startsWith("src/main/") || path.equals("README.md");
        Predicate<String> reviewRelevance = path -> path.equals("src/main/java/App.java") || path.equals("docs/spec.md");

        Set<String> effective = assembler.effectiveReadableFiles(snapshotFiles, backendPolicy, reviewRelevance);

        assertThat(effective).containsExactlyInAnyOrder("src/main/java/App.java");
        assertThat(assembler.effectiveReadableFiles(snapshotFiles, backendPolicy, null))
                .containsExactlyInAnyOrder("src/main/java/App.java", "README.md");
        assertThat(assembler.effectiveReadableFiles(snapshotFiles, backendPolicy, path -> false)).isEmpty();
    }

    @Test
    void derivesReviewRelevanceFromThePersistedScoutConclusion() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ContextScoutConclusion conclusion = new ContextScoutConclusion(
                reviewId,
                2,
                1,
                "summary",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("src/main/java/Query.java"),
                Map.of("BACKEND", List.of("src/main/")),
                "{}",
                Instant.parse("2026-08-10T08:00:00Z"));
        ReviewContextAssembler assembler = new ReviewContextAssembler(storeOf(reviewId, conclusion));

        Predicate<String> backendRelevance = assembler.reviewRelevancePredicate(reviewId, 2, RoleType.BACKEND);
        assertThat(backendRelevance).isNotNull();
        assertThat(backendRelevance.test("src/main/java/Query.java")).isTrue();
        assertThat(backendRelevance.test("src/main/java/Other.java")).isTrue();
        assertThat(backendRelevance.test("frontend/app.js")).isFalse();

        assertThat(assembler.reviewRelevancePredicate(reviewId, 3, RoleType.BACKEND)).isNull();
        assertThat(new ReviewContextAssembler().reviewRelevancePredicate(reviewId, 2, RoleType.BACKEND)).isNull();
    }

    @Test
    void buildsRoleFileGrantsBoundToRoleAttemptAndSnapshotCommit() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ReviewContextAssembler assembler = new ReviewContextAssembler();

        RepositoryFileGrantSet grants = assembler.fileGrants(
                reviewId, 1, RoleType.BACKEND, "c".repeat(40),
                Set.of("src/main/java/App.java", "src/main/java/Other.java"));

        assertThat(grants.size()).isEqualTo(2);
        assertThat(grants.grants())
                .allSatisfy(grant -> {
                    assertThat(grant.reviewId()).isEqualTo(reviewId);
                    assertThat(grant.attemptNo()).isEqualTo(1);
                    assertThat(grant.roleType()).isEqualTo(RoleType.BACKEND);
                    assertThat(grant.snapshotCommit()).isEqualTo("c".repeat(40));
                    assertThat(grants.resolve(grant.fileRef())).contains(grant);
                });
        assertThat(assembler.fileGrants(reviewId, 1, RoleType.FRONTEND, "c".repeat(40), Set.of()).isEmpty()).isTrue();
    }

    @Test
    void keepsCriticalAndAllowedFactsWhenTheContextBudgetIsExceeded() {
        RolePack rolePack = new RolePack(
                RoleType.BACKEND,
                "Backend reviewer",
                List.of("Always"),
                "backend-v1",
                Set.of("requirement-snapshot", "public-claims"),
                List.of(new Checkpoint("backend.api_contract", "Check API", true)),
                Set.of("searchText"),
                Kind.ROLE_ASSESSMENT,
                "role-reviewer",
                Duration.ofSeconds(30),
                4,
                null);
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

    @Test
    void reviewRelevanceReturnsNullForProseOnlyConclusion() {
        ContextScoutConclusion conclusion =
                scoutConclusion(new ReviewId(UUID.randomUUID()), List.of("INIT清单：需求文本…"), Map.of());

        assertThat(ReviewContextAssembler.reviewRelevance(conclusion, RoleType.BACKEND)).isNull();
    }

    @Test
    void reviewRelevanceIgnoresProseAndMatchesOnlyPathLikeEvidence() {
        ContextScoutConclusion conclusion = scoutConclusion(
                new ReviewId(UUID.randomUUID()),
                List.of("src/main/java/A.java", "散文句"),
                Map.of());

        Predicate<String> relevance = ReviewContextAssembler.reviewRelevance(conclusion, RoleType.BACKEND);
        assertThat(relevance).isNotNull();
        assertThat(relevance.test("src/main/java/A.java")).isTrue();
        assertThat(relevance.test("src/main/java/B.java")).isFalse();
    }

    @Test
    void reviewRelevanceReturnsNullForEmptyConclusion() {
        ContextScoutConclusion conclusion = scoutConclusion(new ReviewId(UUID.randomUUID()), List.of(), Map.of());

        assertThat(ReviewContextAssembler.reviewRelevance(conclusion, RoleType.BACKEND)).isNull();
    }

    @Test
    void keepsProseEvidenceOutOfThePublicFactText() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ContextScoutConclusion conclusion = scoutConclusion(
                reviewId,
                List.of("src/main/java/App.java", "INIT清单：需求文本…"),
                Map.of("BACKEND", List.of("src/main/")));
        ReviewContextAssembler assembler = new ReviewContextAssembler(storeOf(reviewId, conclusion));

        ContextFact backendFact = assembler.contextScoutFact(reviewId, 1, RoleType.BACKEND).orElseThrow();
        assertThat(backendFact.publicText())
                .contains("Evidence paths: src/main/java/App.java")
                .doesNotContain("INIT清单");
    }

    @Test
    void omitsEvidencePathsSectionWhenRoleHasNoPathLikeScopes() {
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ContextScoutConclusion conclusion = scoutConclusion(
                reviewId,
                List.of("src/main/java/App.java", "INIT清单：需求文本…"),
                Map.of());
        ReviewContextAssembler assembler = new ReviewContextAssembler(storeOf(reviewId, conclusion));

        ContextFact fact = assembler.contextScoutFact(reviewId, 1, RoleType.BACKEND).orElseThrow();
        assertThat(fact.publicText())
                .doesNotContain("Evidence paths")
                .doesNotContain("src/main/java/App.java")
                .doesNotContain("INIT清单");
    }

    private ContextScoutConclusion scoutConclusion(
            ReviewId reviewId,
            List<String> evidencePaths,
            Map<String, List<String>> roleScopes) {
        return new ContextScoutConclusion(
                reviewId,
                1,
                1,
                "overview",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                evidencePaths,
                roleScopes,
                "{}",
                Instant.parse("2026-08-10T08:00:00Z"));
    }

    private ContextFact fact(String id, String selector, Priority priority, boolean disputed, String text) {
        return new ContextFact(id, selector, priority, disputed, Instant.parse("2026-07-16T00:00:00Z"), text);
    }

    private ContextScoutConclusionStore storeOf(ReviewId reviewId, ContextScoutConclusion conclusion) {
        return new ContextScoutConclusionStore() {
            @Override
            public void save(ContextScoutConclusion ignored) {
            }

            @Override
            public Optional<ContextScoutConclusion> find(ReviewId id, int attemptNo) {
                return reviewId.equals(id) && attemptNo == conclusion.attemptNo()
                        ? Optional.of(conclusion) : Optional.empty();
            }
        };
    }
}
