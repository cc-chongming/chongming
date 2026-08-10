package ai.cc.chongming.review.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cc.chongming.review.domain.model.ContextScoutConclusion;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-023#5] Verifies attempt-scoped in-memory Scout conclusion storage.
 *
 * @author zyj
 */
class InMemoryContextScoutConclusionStoreTests {

    @Test
    void replacesTheSameAttemptIdempotentlyWithoutAffectingOtherAttempts() {
        InMemoryContextScoutConclusionStore store = new InMemoryContextScoutConclusionStore();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        store.save(conclusion(reviewId, 1, "旧结论"));
        store.save(conclusion(reviewId, 1, "重放后的同次结论"));
        store.save(conclusion(reviewId, 2, "新尝试结论"));

        assertThat(store.find(reviewId, 1)).get().extracting(ContextScoutConclusion::summary)
                .isEqualTo("重放后的同次结论");
        assertThat(store.find(reviewId, 2)).get().extracting(ContextScoutConclusion::summary)
                .isEqualTo("新尝试结论");
    }

    private ContextScoutConclusion conclusion(ReviewId reviewId, int attemptNo, String summary) {
        return new ContextScoutConclusion(
                reviewId,
                attemptNo,
                1,
                summary,
                List.of("src/main"),
                List.of("Application"),
                List.of(),
                List.of(),
                List.of("pom.xml"),
                Map.of("BACKEND", List.of("src/main/")),
                "{}",
                Instant.parse("2026-08-10T08:00:00Z"));
    }
}
