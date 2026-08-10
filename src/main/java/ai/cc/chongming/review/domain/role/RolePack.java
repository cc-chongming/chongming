package ai.cc.chongming.review.domain.role;

import ai.cc.chongming.review.domain.gateway.StructuredOutputs.Kind;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

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
        List<Checkpoint> checklist,
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
        checklist = copyCheckpoints(checklist);
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

    /**
     * [AIREVIEW-PLAN-024#方案0] One review checkpoint of a role contract. Legacy plain-text checklist
     * entries keep working as checkpoints without a stable key; structured entries carry a stable
     * {@code checkpointKey} used by assessment submissions and coverage guards.
     *
     * @author wangli
     */
    public record Checkpoint(String checkpointKey, String instruction, boolean required) {

        private static final Pattern STABLE_KEY = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]+)*");

        public Checkpoint {
            if (checkpointKey != null) {
                if (checkpointKey.isBlank() || !STABLE_KEY.matcher(checkpointKey).matches()) {
                    throw new IllegalArgumentException(
                            "checkpointKey must be a stable lower-snake-case identifier: " + checkpointKey);
                }
            }
            if (instruction == null || instruction.isBlank()) {
                throw new IllegalArgumentException("checkpoint instruction must not be blank");
            }
        }

        public boolean hasStableKey() {
            return checkpointKey != null;
        }
    }

    private static List<Checkpoint> copyCheckpoints(List<Checkpoint> checkpoints) {
        if (checkpoints == null || checkpoints.isEmpty()) {
            throw new IllegalArgumentException("checklist must contain at least one checkpoint");
        }
        return List.copyOf(checkpoints);
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
