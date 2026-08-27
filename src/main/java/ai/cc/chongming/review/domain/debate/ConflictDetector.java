package ai.cc.chongming.review.domain.debate;

import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.ReviewAssessment;
import ai.cc.chongming.review.domain.model.ReviewTypes.AssessmentStatus;
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
        return detect(claims, List.of());
    }

    /**
     * [AIREVIEW-PLAN-024#方案4] Detects conflicts from persisted Claims and Assessments aggregated by
     * one stable subject key. A single GAP or a single UNKNOWN assessment is a Gate risk input, never
     * a debate candidate on its own; only mutually contradictory conclusions on the same subject form
     * a candidate. Assessment checkpoint keys are treated as subject keys.
     */
    public ConflictDetectionResult detect(List<Claim> claims, List<ReviewAssessment> assessments) {
        Objects.requireNonNull(claims, "claims must not be null");
        Objects.requireNonNull(assessments, "assessments must not be null");
        Map<String, SubjectFacts> bySubject = new LinkedHashMap<>();
        for (Claim claim : claims) {
            if (claim.status() == ClaimStatus.WITHDRAWN) {
                continue;
            }
            bySubject.computeIfAbsent(normalizedSubject(claim.subjectKey()), ignored -> new SubjectFacts())
                    .claims.add(claim);
        }
        for (ReviewAssessment assessment : assessments) {
            bySubject.computeIfAbsent(normalizedSubject(assessment.checkpointKey()), ignored -> new SubjectFacts())
                    .assessments.add(assessment);
        }
        List<ConflictCandidate> candidates = new ArrayList<>();
        List<NoConflictReason> noConflicts = new ArrayList<>();
        for (Map.Entry<String, SubjectFacts> entry : bySubject.entrySet()) {
            ConflictCandidate candidate = detectSubject(entry.getKey(), entry.getValue());
            if (candidate == null) {
                noConflicts.add(new NoConflictReason(entry.getKey(), "NO_CONTRADICTORY_CONCLUSION"));
            } else {
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.comparingInt(ConflictCandidate::score).reversed()
                .thenComparing(ConflictCandidate::subjectKey));
        noConflicts.sort(Comparator.comparing(NoConflictReason::subjectKey));
        return new ConflictDetectionResult(List.copyOf(candidates), List.copyOf(noConflicts));
    }

    private ConflictCandidate detectSubject(String subjectKey, SubjectFacts facts) {
        List<Claim> claims = facts.claims;
        List<ReviewAssessment> assessments = facts.assessments;
        if (claims.isEmpty() && assessments.isEmpty()) {
            return null;
        }
        Set<ConflictRule> rules = EnumSet.noneOf(ConflictRule.class);
        boolean hasSupport = claims.stream().anyMatch(claim -> claim.position() == ClaimPosition.SUPPORT);
        boolean hasOppose = claims.stream().anyMatch(claim -> claim.position() == ClaimPosition.OPPOSE);
        boolean hasConfirmed = assessments.stream().anyMatch(a -> a.status() == AssessmentStatus.CONFIRMED);
        boolean hasGap = assessments.stream().anyMatch(a -> a.status() == AssessmentStatus.GAP);
        if (hasSupport && hasOppose) {
            rules.add(ConflictRule.OPPOSING_POSITION);
        }
        // [AIREVIEW-PLAN-033#3.1] Objections from different roles on the same subject can still conflict
        // with each other (divergent diagnoses or incompatible remedies). Surface such subjects even
        // without a SUPPORT position so the debate can reconcile the objections themselves — unanimous
        // opposition is not the absence of conflict.
        long opposingRoles = claims.stream()
                .filter(claim -> claim.position() == ClaimPosition.OPPOSE)
                .map(Claim::roleType)
                .distinct()
                .count();
        if (opposingRoles >= 2) {
            rules.add(ConflictRule.OPPOSE_DIVERGENCE);
        }
        // [AIREVIEW-PLAN-024#方案4] A positive conclusion (CONFIRMED assessment or SUPPORT claim)
        // contradicts a negative conclusion (GAP assessment or OPPOSE claim) on the same subject.
        // A lone GAP/UNKNOWN is a risk, not a conflict.
        boolean contradictory = (hasSupport || hasConfirmed) && (hasOppose || hasGap);
        if (contradictory && (hasConfirmed || hasGap)) {
            rules.add(ConflictRule.ASSESSMENT_STATUS_CONFLICT);
        }
        if (claims.size() >= 2) {
            int lowestSeverity = claims.stream().mapToInt(claim -> claim.severity().ordinal()).min().orElse(0);
            int highestSeverity = claims.stream().mapToInt(claim -> claim.severity().ordinal()).max().orElse(0);
            if (highestSeverity - lowestSeverity >= 2) {
                rules.add(ConflictRule.SEVERITY_MISMATCH);
            }
        }
        if (hasContradictoryEvidence(claims)) {
            rules.add(ConflictRule.CONTRADICTORY_EVIDENCE);
        }
        if (rules.isEmpty()) {
            return null;
        }
        boolean opposing = rules.contains(ConflictRule.OPPOSING_POSITION)
                || rules.contains(ConflictRule.ASSESSMENT_STATUS_CONFLICT);
        int score = opposing ? 80 : rules.contains(ConflictRule.OPPOSE_DIVERGENCE) ? 40 : 20;
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

    /** Per-subject aggregation of persisted claims and assessment conclusions. */
    private static final class SubjectFacts {
        private final List<Claim> claims = new ArrayList<>();
        private final List<ReviewAssessment> assessments = new ArrayList<>();
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
        ASSESSMENT_STATUS_CONFLICT,
        SEVERITY_MISMATCH,
        CONTRADICTORY_EVIDENCE,
        OPPOSE_DIVERGENCE
    }

    /**
     * [AIREVIEW-PLAN-024#方案4] claimIds may be empty when the contradiction is carried purely by
     * Assessment conclusions; every other field keeps its previous contract.
     *
     * @author wangli
     */
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
