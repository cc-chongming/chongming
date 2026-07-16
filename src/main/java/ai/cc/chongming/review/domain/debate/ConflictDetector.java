package ai.cc.chongming.review.domain.debate;

import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimPosition;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimSeverity;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministically recalls conflicting public Claims before a model is allowed to rank or summarize them.
 *
 * @author wangli
 */
public final class ConflictDetector {

    /**
     * Detects only persisted, non-withdrawn claims and returns a stable ordering for director planning.
     */
    public ConflictDetectionResult detect(List<Claim> claims) {
        Objects.requireNonNull(claims, "claims must not be null");
        Map<String, List<Claim>> bySubject = new LinkedHashMap<>();
        for (Claim claim : claims) {
            if (claim.status() == ClaimStatus.WITHDRAWN) {
                continue;
            }
            bySubject.computeIfAbsent(normalizedSubject(claim.subjectKey()), ignored -> new ArrayList<>()).add(claim);
        }
        List<ConflictCandidate> candidates = new ArrayList<>();
        List<NoConflictReason> noConflicts = new ArrayList<>();
        for (Map.Entry<String, List<Claim>> entry : bySubject.entrySet()) {
            ConflictCandidate candidate = detectSubject(entry.getKey(), entry.getValue());
            if (candidate == null) {
                noConflicts.add(new NoConflictReason(entry.getKey(), "NO_OPPOSING_OR_SEVERITY_CONFLICT"));
            } else {
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.comparingInt(ConflictCandidate::score).reversed()
                .thenComparing(ConflictCandidate::subjectKey));
        noConflicts.sort(Comparator.comparing(NoConflictReason::subjectKey));
        return new ConflictDetectionResult(List.copyOf(candidates), List.copyOf(noConflicts));
    }

    private ConflictCandidate detectSubject(String subjectKey, List<Claim> claims) {
        if (claims.size() < 2) {
            return null;
        }
        Set<ConflictRule> rules = EnumSet.noneOf(ConflictRule.class);
        boolean hasSupport = claims.stream().anyMatch(claim -> claim.position() == ClaimPosition.SUPPORT);
        boolean hasOppose = claims.stream().anyMatch(claim -> claim.position() == ClaimPosition.OPPOSE);
        if (hasSupport && hasOppose) {
            rules.add(ConflictRule.OPPOSING_POSITION);
        }
        int lowestSeverity = claims.stream().mapToInt(claim -> claim.severity().ordinal()).min().orElse(0);
        int highestSeverity = claims.stream().mapToInt(claim -> claim.severity().ordinal()).max().orElse(0);
        if (highestSeverity - lowestSeverity >= 2) {
            rules.add(ConflictRule.SEVERITY_MISMATCH);
        }
        if (hasContradictoryEvidence(claims)) {
            rules.add(ConflictRule.CONTRADICTORY_EVIDENCE);
        }
        if (rules.isEmpty()) {
            return null;
        }
        int score = rules.contains(ConflictRule.OPPOSING_POSITION) ? 80 : 20;
        score += claims.stream().mapToInt(claim -> severityWeight(claim.severity())).max().orElse(0);
        if (rules.contains(ConflictRule.SEVERITY_MISMATCH)) {
            score += 10;
        }
        if (rules.contains(ConflictRule.CONTRADICTORY_EVIDENCE)) {
            score += 15;
        }
        List<ClaimId> claimIds = claims.stream().map(Claim::claimId)
                .sorted(Comparator.comparing(ClaimId::value)).toList();
        return new ConflictCandidate(subjectKey, claimIds, rules, score, explanation(rules));
    }

    private boolean hasContradictoryEvidence(List<Claim> claims) {
        Set<EvidenceId> supportEvidence = claims.stream()
                .filter(claim -> claim.position() == ClaimPosition.SUPPORT)
                .flatMap(claim -> claim.evidenceReferences().stream())
                .map(reference -> reference.evidenceId())
                .collect(java.util.stream.Collectors.toSet());
        return !supportEvidence.isEmpty() && claims.stream()
                .filter(claim -> claim.position() == ClaimPosition.OPPOSE)
                .flatMap(claim -> claim.evidenceReferences().stream())
                .map(reference -> reference.evidenceId())
                .anyMatch(supportEvidence::contains);
    }

    private int severityWeight(ClaimSeverity severity) {
        return switch (severity) {
            case P0 -> 20;
            case P1 -> 15;
            case P2 -> 10;
            case P3 -> 5;
        };
    }

    private String explanation(Set<ConflictRule> rules) {
        return rules.stream().map(Enum::name).sorted().reduce((left, right) -> left + "," + right).orElseThrow();
    }

    private String normalizedSubject(String subjectKey) {
        return subjectKey.trim().toLowerCase(Locale.ROOT);
    }

    /** @author wangli */
    public enum ConflictRule {
        OPPOSING_POSITION,
        SEVERITY_MISMATCH,
        CONTRADICTORY_EVIDENCE
    }

    /** @author wangli */
    public record ConflictCandidate(
            String subjectKey,
            List<ClaimId> claimIds,
            Set<ConflictRule> rules,
            int score,
            String explanation) {

        public ConflictCandidate {
            Objects.requireNonNull(subjectKey, "subjectKey must not be null");
            claimIds = List.copyOf(claimIds);
            rules = Set.copyOf(rules);
            if (score < 1) {
                throw new IllegalArgumentException("score must be positive");
            }
            Objects.requireNonNull(explanation, "explanation must not be null");
        }
    }

    /** @author wangli */
    public record NoConflictReason(String subjectKey, String reason) {
        public NoConflictReason {
            Objects.requireNonNull(subjectKey, "subjectKey must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /** @author wangli */
    public record ConflictDetectionResult(List<ConflictCandidate> candidates, List<NoConflictReason> noConflicts) {
        public ConflictDetectionResult {
            candidates = List.copyOf(candidates);
            noConflicts = List.copyOf(noConflicts);
        }
    }
}
