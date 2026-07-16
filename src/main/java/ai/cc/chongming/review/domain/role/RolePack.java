package ai.cc.chongming.review.domain.role;

import ai.cc.chongming.review.domain.gateway.StructuredOutputs.Kind;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned role contract containing responsibilities, bounded tools and a public output schema.
 *
 * @author wangli
 */
public record RolePack(
        RoleType roleType,
        String description,
        List<String> activationRules,
        String promptVersion,
        Set<String> contextSelectors,
        List<String> checklist,
        Set<String> allowedTools,
        Kind outputKind,
        String modelProfile,
        Duration timeout,
        int maxIterations) {

    public RolePack {
        Objects.requireNonNull(roleType, "roleType must not be null");
        requireText(description, "description");
        activationRules = copyNonBlank(activationRules, "activationRules");
        requireText(promptVersion, "promptVersion");
        contextSelectors = Set.copyOf(contextSelectors);
        checklist = copyNonBlank(checklist, "checklist");
        allowedTools = Set.copyOf(allowedTools);
        Objects.requireNonNull(outputKind, "outputKind must not be null");
        requireText(modelProfile, "modelProfile");
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxIterations < 1 || maxIterations > 20) {
            throw new IllegalArgumentException("maxIterations must be between 1 and 20");
        }
    }

    private static List<String> copyNonBlank(List<String> values, String name) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " must contain non-blank values");
        }
        return List.copyOf(values);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
