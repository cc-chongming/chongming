package ai.cc.chongming.review.domain.role;

import ai.cc.chongming.review.domain.gateway.StructuredOutputs.Kind;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.role.RolePack.Checkpoint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Loads static RolePack YAML resources and rejects roles that request tools outside the server whitelist.
 *
 * @author wangli
 */
@Component
public class RolePackRegistry {

    private static final Set<String> ALLOWED_TOOL_NAMES = Set.of(
            "listFiles",
            "searchText",
            "findSymbol",
            "readLines",
            "getFileMetadata",
            "submitEvidence",
            "submit_assessment",
            "submit_claim",
            "complete_initial_review",
            "list_persisted_debate_topics",
            "list_conflict_candidates",
            "register_topics",
            "close_debate_topic",
            "begin_second_debate_round",
            "begin_judging",
            "submit_challenge",
            "submit_rebuttal",
            "change_claim_position",
            "request_additional_evidence",
            "submit_judgement",
            "draft_gate",
            "loadRequirementSection",
            "loadReviewFacts",
            "loadDebateContext");

    private final Map<RoleType, RolePack> rolePacks;

    public RolePackRegistry(ResourcePatternResolver resourcePatternResolver) {
        Objects.requireNonNull(resourcePatternResolver, "resourcePatternResolver must not be null");
        try {
            this.rolePacks = Map.copyOf(load(resourcePatternResolver.getResources("classpath*:roles/*.yml")));
        } catch (Exception exception) {
            throw new IllegalStateException("RolePack resources cannot be loaded", exception);
        }
    }

    /**
     * Resolves one enabled role's static contract.
     *
     * @param roleType requested role
     * @return immutable RolePack
     */
    public RolePack require(RoleType roleType) {
        RolePack rolePack = rolePacks.get(Objects.requireNonNull(roleType, "roleType must not be null"));
        if (rolePack == null) {
            throw new IllegalArgumentException("RolePack is not configured for " + roleType);
        }
        return rolePack;
    }

    /**
     * Returns all static packs without exposing prompt-private runtime state.
     *
     * @return immutable role packs
     */
    public Collection<RolePack> all() {
        return rolePacks.values();
    }

    private Map<RoleType, RolePack> load(Resource[] resources) {
        Map<RoleType, RolePack> values = new LinkedHashMap<>();
        for (Resource resource : resources) {
            RolePack rolePack = read(resource);
            if (values.putIfAbsent(rolePack.roleType(), rolePack) != null) {
                throw new IllegalArgumentException("Duplicate RolePack: " + rolePack.roleType());
            }
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private RolePack read(Resource resource) {
        YamlMapFactoryBean yaml = new YamlMapFactoryBean();
        yaml.setResources(resource);
        Map<String, Object> values = yaml.getObject();
        if (values == null) {
            throw new IllegalArgumentException("RolePack resource is empty: " + resource.getFilename());
        }
        RolePack rolePack = new RolePack(
                RoleType.valueOf(text(values, "roleType").toUpperCase(Locale.ROOT)),
                text(values, "description"),
                textList(values, "activationRules"),
                text(values, "promptVersion"),
                Set.copyOf(textList(values, "contextSelectors")),
                checkpoints(values, resource.getFilename()),
                Set.copyOf(textList(values, "allowedTools")),
                Kind.valueOf(text(values, "outputKind").toUpperCase(Locale.ROOT)),
                text(values, "modelProfile"),
                Duration.parse(text(values, "timeout")),
                integer(values, "maxIterations"),
                voice(values, resource.getFilename()));
        if (!ALLOWED_TOOL_NAMES.containsAll(rolePack.allowedTools())) {
            throw new IllegalArgumentException("RolePack requests a non-whitelisted tool: " + rolePack.roleType());
        }
        requireCheckpointContract(rolePack, resource.getFilename());
        return rolePack;
    }

    /**
     * [AIREVIEW-PLAN-024#方案0] Parses checklist entries in both shapes: structured
     * {@code checkpointKey + instruction + required} entries and legacy plain-text entries, which are
     * kept as checkpoints without a stable key during the compatibility transition.
     */
    private List<Checkpoint> checkpoints(Map<String, Object> values, String filename) {
        Object value = values.get("checklist");
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("RolePack checklist must be a non-empty list: " + filename);
        }
        Set<String> keys = new HashSet<>();
        List<Checkpoint> checkpoints = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String text) {
                if (text.isBlank()) {
                    throw new IllegalArgumentException("RolePack checklist contains blank value: " + filename);
                }
                checkpoints.add(new Checkpoint(null, text, false));
            } else if (item instanceof Map<?, ?> entry) {
                Checkpoint checkpoint = checkpoint(entry, filename);
                if (!keys.add(checkpoint.checkpointKey())) {
                    throw new IllegalArgumentException(
                            "RolePack checklist contains duplicate checkpointKey " + checkpoint.checkpointKey()
                                    + ": " + filename);
                }
                checkpoints.add(checkpoint);
            } else {
                throw new IllegalArgumentException(
                        "RolePack checklist entry must be text or a checkpoint map: " + filename);
            }
        }
        return List.copyOf(checkpoints);
    }

    private Checkpoint checkpoint(Map<?, ?> entry, String filename) {
        Object key = entry.get("checkpointKey");
        Object instruction = entry.get("instruction");
        if (!(key instanceof String keyText) || keyText.isBlank()) {
            throw new IllegalArgumentException("RolePack checkpoint must declare checkpointKey: " + filename);
        }
        if (!(instruction instanceof String instructionText) || instructionText.isBlank()) {
            throw new IllegalArgumentException(
                    "RolePack checkpoint " + keyText + " must declare instruction: " + filename);
        }
        Object required = entry.get("required");
        boolean requiredFlag = required instanceof Boolean flag && flag;
        return new Checkpoint(keyText, instructionText, requiredFlag);
    }

    /**
     * [AIREVIEW-PLAN-024#方案0] The four core roles must expose only stable-key checkpoints and at
     * least one required checkpoint; legacy text entries remain allowed for optional roles.
     */
    private void requireCheckpointContract(RolePack rolePack, String filename) {
        if (!rolePack.roleType().isCore()) {
            return;
        }
        for (Checkpoint checkpoint : rolePack.checklist()) {
            if (!checkpoint.hasStableKey()) {
                throw new IllegalArgumentException(
                        "Core role " + rolePack.roleType() + " checklist entry lacks a stable checkpointKey: "
                                + filename);
            }
        }
        if (rolePack.checklist().stream().noneMatch(Checkpoint::required)) {
            throw new IllegalArgumentException(
                    "Core role " + rolePack.roleType() + " must declare at least one required checkpoint: "
                            + filename);
        }
    }

    private String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("RolePack property must be non-blank: " + key);
        }
        return text;
    }

    private List<String> textList(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("RolePack property must be a list: " + key);
        }
        return list.stream().map(item -> {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("RolePack list contains blank value: " + key);
            }
            return text;
        }).toList();
    }

    /**
     * [AIREVIEW-PLAN-032#1.2] Parses the optional {@code voice:} block carrying the
     * role-mother-tongue expression guidance. A missing block keeps the legacy empty voice;
     * a present but empty block is rejected so a voice is either absent or meaningful.
     */
    @SuppressWarnings("unchecked")
    private RolePack.Voice voice(Map<String, Object> values, String filename) {
        Object raw = values.get("voice");
        if (!(raw instanceof Map<?, ?> voiceMap)) {
            return RolePack.Voice.EMPTY;
        }
        Object identityRaw = voiceMap.get("identity");
        String identity = identityRaw instanceof String identityText && !identityText.isBlank()
                ? identityText : null;
        Object lensRaw = voiceMap.get("lens");
        String lens = lensRaw instanceof String lensText && !lensText.isBlank() ? lensText : null;
        RolePack.Voice voice = new RolePack.Voice(
                identity,
                optionalList(voiceMap.get("focus"), "voice.focus", filename),
                optionalList(voiceMap.get("avoid"), "voice.avoid", filename),
                lens);
        if (voice.isEmpty()) {
            throw new IllegalArgumentException("RolePack voice block must not be empty: " + filename);
        }
        return voice;
    }

    private List<String> optionalList(Object raw, String key, String filename) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("RolePack property must be a list: " + key + ": " + filename);
        }
        return list.stream().map(item -> {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("RolePack list contains blank value: " + key + ": " + filename);
            }
            return text;
        }).toList();
    }

    private int integer(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("RolePack property must be numeric: " + key);
    }
}
