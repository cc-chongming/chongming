package ai.cc.chongming.review.domain.model;

import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * [AIREVIEW-PLAN-023#5] Public, attempt-scoped conclusion produced by Context Scout.
 * Hidden reasoning, system prompts and repository file contents are intentionally not representable.
 *
 * @author zyj
 */
public record ContextScoutConclusion(
        ReviewId reviewId,
        int attemptNo,
        int schemaVersion,
        String summary,
        List<String> moduleRoots,
        List<String> entryPoints,
        List<String> constraints,
        List<String> risks,
        List<String> evidencePaths,
        Map<String, List<String>> roleScopes,
        String rawPublicResult,
        Instant createdAt) {

    public ContextScoutConclusion {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        summary = requireText(summary, "summary");
        moduleRoots = immutableTextList(moduleRoots, "moduleRoots");
        entryPoints = immutableTextList(entryPoints, "entryPoints");
        constraints = immutableTextList(constraints, "constraints");
        risks = immutableTextList(risks, "risks");
        evidencePaths = immutableTextList(evidencePaths, "evidencePaths");
        roleScopes = immutableRoleScopes(roleScopes);
        rawPublicResult = requireText(rawPublicResult, "rawPublicResult");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public String reference() {
        return reviewId.value() + ":" + attemptNo;
    }

    private static List<String> immutableTextList(List<String> values, String name) {
        Objects.requireNonNull(values, name + " must not be null");
        List<String> copy = values.stream().map(value -> requireText(value, name + " item")).toList();
        return List.copyOf(copy);
    }

    private static Map<String, List<String>> immutableRoleScopes(Map<String, List<String>> roleScopes) {
        Objects.requireNonNull(roleScopes, "roleScopes must not be null");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        roleScopes.forEach((role, scopes) -> copy.put(
                requireText(role, "roleScopes role"), immutableTextList(scopes, "roleScopes scopes")));
        return Map.copyOf(copy);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
