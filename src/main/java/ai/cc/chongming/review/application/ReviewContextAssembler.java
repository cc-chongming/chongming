package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.model.ContextScoutConclusion;
import ai.cc.chongming.review.domain.repository.ContextScoutConclusionStore;
import ai.cc.chongming.review.domain.role.RolePack;
import ai.cc.chongming.review.infrastructure.agentscope.tool.RepositoryFileGrant;
import ai.cc.chongming.review.infrastructure.agentscope.tool.RepositoryFileGrantSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
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
     * [AIREVIEW-PLAN-024] Computes {@code effectiveReadableFiles = snapshotFiles ∩ rolePathPolicy ∩
     * reviewRelevantFiles} in a single pass over the snapshot file set. No per-file lookup, query or
     * nested scan is performed; a missing {@code reviewRelevance} predicate (no Scout conclusion)
     * leaves the intersection limited to the role path policy.
     *
     * @param snapshotFiles normalized paths frozen in the review snapshot
     * @param rolePathPolicy static RolePack path policy
     * @param reviewRelevance review-relevance predicate from the persisted Scout conclusion, or null
     * @return the role's effective readable files, deterministically ordered
     */
    public Set<String> effectiveReadableFiles(
            Collection<String> snapshotFiles,
            Predicate<String> rolePathPolicy,
            Predicate<String> reviewRelevance) {
        Objects.requireNonNull(snapshotFiles, "snapshotFiles must not be null");
        Objects.requireNonNull(rolePathPolicy, "rolePathPolicy must not be null");
        Set<String> effective = new LinkedHashSet<>();
        for (String file : snapshotFiles) {
            if (file == null || file.isBlank()) {
                continue;
            }
            String normalized = file.replace('\\', '/');
            if (!rolePathPolicy.test(normalized)) {
                continue;
            }
            if (reviewRelevance != null && !reviewRelevance.test(normalized)) {
                continue;
            }
            effective.add(normalized);
        }
        return Set.copyOf(effective);
    }

    /**
     * [AIREVIEW-PLAN-024] Derives the review-relevance predicate from the persisted Scout
     * conclusion: a file is relevant when it appears in the Scout evidence paths or lives beneath a
     * Scout-declared scope for this role. Returns {@code null} when no conclusion constrains this
     * attempt, in which case relevance imposes no additional restriction.
     */
    public Predicate<String> reviewRelevancePredicate(ReviewId reviewId, int attemptNo, RoleType roleType) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(roleType, "roleType must not be null");
        if (attemptNo < 1 || conclusionStore == null) {
            return null;
        }
        return conclusionStore.find(reviewId, attemptNo)
                .map(conclusion -> reviewRelevance(conclusion, roleType))
                .orElse(null);
    }

    /**
     * [AIREVIEW-PLAN-024] Builds the role's unguessable fileRef grant set from its effective
     * readable files; every grant is bound to review, attempt, role and snapshot commit.
     */
    public RepositoryFileGrantSet fileGrants(
            ReviewId reviewId,
            int attemptNo,
            RoleType roleType,
            String snapshotCommit,
            Collection<String> effectiveFiles) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(roleType, "roleType must not be null");
        Objects.requireNonNull(effectiveFiles, "effectiveFiles must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        if (snapshotCommit == null || snapshotCommit.isBlank()) {
            throw new IllegalArgumentException("snapshotCommit must not be blank");
        }
        List<RepositoryFileGrant> grants = new ArrayList<>();
        for (String file : effectiveFiles) {
            grants.add(RepositoryFileGrant.issue(reviewId, attemptNo, roleType, snapshotCommit, file));
        }
        return RepositoryFileGrantSet.of(grants);
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
        addSection(sections, "Evidence paths", scopedEvidencePaths(conclusion, roleType));
        addSection(sections, "Authorized role scope", conclusion.roleScopes().getOrDefault(roleType.name(), List.of()));
        return String.join("\n", sections);
    }

    /**
     * [AIREVIEW-PLAN-024] [AIREVIEW-PLAN-062#3] Only path-like evidence paths inside the
     * path-like Scout-declared scope of this role are rendered into role context, so prose
     * sentences and ungranted file paths never reach a role prompt.
     */
    private static List<String> scopedEvidencePaths(ContextScoutConclusion conclusion, RoleType roleType) {
        List<String> pathLikeScopes = conclusion.roleScopes().getOrDefault(roleType.name(), List.of()).stream()
                .filter(ReviewContextAssembler::pathLike)
                .map(scope -> scope.replace('\\', '/').trim())
                .toList();
        if (pathLikeScopes.isEmpty()) {
            return List.of();
        }
        return conclusion.evidencePaths().stream()
                .filter(ReviewContextAssembler::pathLike)
                .map(path -> path.replace('\\', '/').trim())
                .filter(path -> matchesAnyScope(path, pathLikeScopes))
                .toList();
    }

    private static boolean matchesAnyScope(String path, List<String> scopes) {
        String normalized = path.replace('\\', '/');
        return scopes.stream().anyMatch(scope -> !scope.isEmpty()
                && (normalized.equals(scope)
                        || normalized.startsWith(scope)
                        || (scope.endsWith("/") && normalized.contains("/" + scope))));
    }

    /**
     * [AIREVIEW-PLAN-062#1] Path-like strings only: normalized without whitespace, ASCII-only
     * (below U+2E80) and containing a slash or a dot. Natural-language prose is filtered out.
     */
    private static boolean pathLike(String value) {
        String normalized = value == null ? "" : value.replace('\\', '/').trim();
        return !normalized.isEmpty()
                && normalized.indexOf(' ') < 0
                && normalized.chars().allMatch(c -> c < 0x2E80)
                && (normalized.contains("/") || normalized.contains("."));
    }

    /**
     * [AIREVIEW-PLAN-024] [AIREVIEW-PLAN-062#2] Builds the review-relevance predicate from the
     * Scout conclusion: a file is relevant when it is a path-like evidence path or lives beneath a
     * path-like Scout-declared scope for this role. Returns {@code null} when neither path-like
     * evidence nor path-like scopes exist, leaving relevance unconstrained.
     */
    static Predicate<String> reviewRelevance(ContextScoutConclusion conclusion, RoleType roleType) {
        Set<String> pathLikeEvidence = new HashSet<>(conclusion.evidencePaths().stream()
                .filter(ReviewContextAssembler::pathLike)
                .map(path -> path.replace('\\', '/').trim())
                .toList());
        List<String> pathLikeScopes = conclusion.roleScopes().getOrDefault(roleType.name(), List.of()).stream()
                .filter(ReviewContextAssembler::pathLike)
                .map(scope -> scope.replace('\\', '/').trim())
                .toList();
        if (pathLikeEvidence.isEmpty() && pathLikeScopes.isEmpty()) {
            return null;
        }
        return path -> pathLikeEvidence.contains(path) || matchesAnyScope(path, pathLikeScopes);
    }

    private static void addSection(List<String> sections, String label, List<String> values) {
        if (!values.isEmpty()) {
            sections.add(label + ": " + String.join(", ", values));
        }
    }
}
