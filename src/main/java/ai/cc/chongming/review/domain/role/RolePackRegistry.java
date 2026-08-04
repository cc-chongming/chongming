package ai.cc.chongming.review.domain.role;

import ai.cc.chongming.review.domain.gateway.StructuredOutputs.Kind;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import java.time.Duration;
import java.util.Collection;
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
            "submit_claim",
            "complete_initial_review",
            "list_persisted_debate_topics",
            "open_debate_topic",
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
                textList(values, "checklist"),
                Set.copyOf(textList(values, "allowedTools")),
                Kind.valueOf(text(values, "outputKind").toUpperCase(Locale.ROOT)),
                text(values, "modelProfile"),
                Duration.parse(text(values, "timeout")),
                integer(values, "maxIterations"));
        if (!ALLOWED_TOOL_NAMES.containsAll(rolePack.allowedTools())) {
            throw new IllegalArgumentException("RolePack requests a non-whitelisted tool: " + rolePack.roleType());
        }
        return rolePack;
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

    private int integer(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("RolePack property must be numeric: " + key);
    }
}
