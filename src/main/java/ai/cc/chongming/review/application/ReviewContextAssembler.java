package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.model.ContextScoutConclusion;
import ai.cc.chongming.review.domain.repository.ContextScoutConclusionStore;
import ai.cc.chongming.review.domain.role.RolePack;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * [AIREVIEW-PLAN-023#5] Builds a bounded role view and reloads the attempt's persisted Scout conclusion.
 *
 * @author zyj
 */
@Component
public class ReviewContextAssembler {

    private final ContextScoutConclusionStore conclusionStore;

    public ReviewContextAssembler() {
        this.conclusionStore = null;
    }

    @Autowired
    public ReviewContextAssembler(ContextScoutConclusionStore conclusionStore) {
        this.conclusionStore = Objects.requireNonNull(conclusionStore, "conclusionStore must not be null");
    }

    /**
     * Reloads the successful Scout conclusion as one selector-addressable public fact.
     * The raw result remains available to the read model but is not copied into every role prompt.
     */
    public Optional<ContextFact> contextScoutFact(ReviewId reviewId, int attemptNo, RoleType roleType) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(roleType, "roleType must not be null");
        if (attemptNo < 1 || conclusionStore == null) {
            return Optional.empty();
        }
        return conclusionStore.find(reviewId, attemptNo)
                .map(conclusion -> new ContextFact(
                        "scout-overview",
                        "scout-overview",
                        Priority.HIGH,
                        false,
                        conclusion.createdAt(),
                        conclusionPublicText(conclusion, roleType)));
    }

    /**
     * Selects only facts allowed by the RolePack and retains critical, disputed and recent facts first.
     *
     * @param request server-assembled public facts and context budget
     * @return isolated, bounded role context
     */
    public AssembledContext assemble(ContextRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        List<ContextFact> candidates = request.sharedFacts().stream()
                .filter(fact -> request.rolePack().contextSelectors().contains(fact.selector()))
                .sorted(Comparator.comparing(ContextFact::priority)
                        .thenComparing(ContextFact::disputed, Comparator.reverseOrder())
                        .thenComparing(ContextFact::updatedAt, Comparator.reverseOrder())
                        .thenComparing(ContextFact::factId))
                .toList();
        List<ContextFact> selected = new ArrayList<>();
        int usedCharacters = 0;
        boolean truncated = false;
        for (ContextFact fact : candidates) {
            int factLength = fact.publicText().length();
            if (usedCharacters + factLength <= request.characterBudget() || fact.priority() == Priority.CRITICAL) {
                selected.add(fact);
                usedCharacters += factLength;
            } else {
                truncated = true;
            }
        }
        return new AssembledContext(
                request.reviewId(), request.rolePack().roleType(), List.copyOf(selected), usedCharacters, truncated);
    }

    /**
     * Input populated by batch-oriented persistence/query code; it intentionally accepts no private role history.
     *
     * @author wangli
     */
    public record ContextRequest(
            ReviewId reviewId, RolePack rolePack, List<ContextFact> sharedFacts, int characterBudget) {

        public ContextRequest {
            Objects.requireNonNull(reviewId, "reviewId must not be null");
            Objects.requireNonNull(rolePack, "rolePack must not be null");
            sharedFacts = List.copyOf(sharedFacts);
            if (characterBudget < 1) {
                throw new IllegalArgumentException("characterBudget must be positive");
            }
        }
    }

    /**
     * One public, pre-batch-loaded fact. Prompt-private reasoning is deliberately not representable here.
     *
     * @author wangli
     */
    public record ContextFact(
            String factId, String selector, Priority priority, boolean disputed, Instant updatedAt, String publicText) {

        public ContextFact {
            requireText(factId, "factId");
            requireText(selector, "selector");
            Objects.requireNonNull(priority, "priority must not be null");
            Objects.requireNonNull(updatedAt, "updatedAt must not be null");
            requireText(publicText, "publicText");
        }
    }

    /**
     * Stable fact priority used for budget truncation.
     *
     * @author wangli
     */
    public enum Priority {
        CRITICAL,
        HIGH,
        NORMAL
    }

    /**
     * Isolated public role view ready for prompt assembly.
     *
     * @author wangli
     */
    public record AssembledContext(
            ReviewId reviewId, RoleType roleType, List<ContextFact> facts, int characterCount, boolean truncated) {

        public AssembledContext {
            Objects.requireNonNull(reviewId, "reviewId must not be null");
            Objects.requireNonNull(roleType, "roleType must not be null");
            facts = List.copyOf(facts);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static String conclusionPublicText(ContextScoutConclusion conclusion, RoleType roleType) {
        List<String> sections = new ArrayList<>();
        sections.add("Context Scout overview: " + conclusion.summary());
        addSection(sections, "Module roots", conclusion.moduleRoots());
        addSection(sections, "Entry points", conclusion.entryPoints());
        addSection(sections, "Constraints", conclusion.constraints());
        addSection(sections, "Risks", conclusion.risks());
        addSection(sections, "Evidence paths", conclusion.evidencePaths());
        addSection(sections, "Authorized role scope", conclusion.roleScopes().getOrDefault(roleType.name(), List.of()));
        return String.join("\n", sections);
    }

    private static void addSection(List<String> sections, String label, List<String> values) {
        if (!values.isEmpty()) {
            sections.add(label + ": " + String.join(", ", values));
        }
    }
}
