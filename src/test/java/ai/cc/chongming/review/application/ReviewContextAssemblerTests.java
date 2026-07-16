package ai.cc.chongming.review.application;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cc.chongming.review.application.ReviewContextAssembler.ContextFact;
import ai.cc.chongming.review.application.ReviewContextAssembler.ContextRequest;
import ai.cc.chongming.review.application.ReviewContextAssembler.Priority;
import ai.cc.chongming.review.domain.gateway.StructuredOutputs.Kind;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.role.RolePack;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests deterministic public-context selection without private-history leakage or per-fact loading.
 *
 * @author wangli
 */
class ReviewContextAssemblerTests {

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
