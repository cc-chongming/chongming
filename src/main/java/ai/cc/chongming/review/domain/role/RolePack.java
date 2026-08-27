package ai.cc.chongming.review.domain.role;

import ai.cc.chongming.review.domain.gateway.StructuredOutputs.Kind;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * [AIREVIEW-PLAN-032#1.1] Versioned role contract containing responsibilities, bounded tools,
 * a public output schema and role-mother-tongue {@link Voice} expression guidance.
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
        int maxIterations,
        Voice voice) {

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
        voice = voice == null ? Voice.EMPTY : voice;
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

    /**
     * [AIREVIEW-PLAN-032#1.1] Role-mother-tongue expression guidance rendered into the role system
     * prompt: Chinese identity, professional vocabulary, forbidden machine identifiers and a
     * checkpoint lens. An absent or all-empty {@code voice} keeps legacy prompts unchanged.
     *
     * @author wangli
     */
    public record Voice(
            String identity,
            List<String> focus,
            List<String> avoid,
            String lens) {

        /** Voice with no content; legacy role packs without a voice block render no role guidance. */
        public static final Voice EMPTY = new Voice(null, List.of(), List.of(), null);

        public Voice {
            if (identity != null && identity.isBlank()) {
                throw new IllegalArgumentException("voice.identity must not be blank");
            }
            if (lens != null && lens.isBlank()) {
                throw new IllegalArgumentException("voice.lens must not be blank");
            }
            focus = normalizeList(focus, "voice.focus");
            avoid = normalizeList(avoid, "voice.avoid");
        }

        /** True when no expression guidance is configured for the role. */
        public boolean isEmpty() {
            return identity == null && focus.isEmpty() && avoid.isEmpty() && lens == null;
        }

        private static List<String> normalizeList(List<String> values, String name) {
            if (values == null) {
                return List.of();
            }
            if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException(name + " must not contain blank values");
            }
            return List.copyOf(values);
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
