package ai.cc.chongming.review.infrastructure.model;

import ai.cc.chongming.review.config.ModelProfilesProperties;
import ai.cc.chongming.review.domain.gateway.ModelGatewayException;
import ai.cc.chongming.review.domain.gateway.ModelGatewayException.Code;
import ai.cc.chongming.review.domain.gateway.ModelProfile;
import ai.cc.chongming.review.domain.gateway.ModelProfile.RetryPolicy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Resolves immutable logical profiles without exposing provider identifiers to RolePacks.
 *
 * [AIREVIEW-PLAN-023#8]
 *
 * @author zyj
 */
@Component
public class ModelProfileRegistry {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_BACKOFF = Duration.ofMillis(200);
    private static final int DEFAULT_MAX_TOKENS = 2_048;

    private final Map<String, ModelProfile> profiles;

    public ModelProfileRegistry(ModelProfilesProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        Map<String, ModelProfile> values = new LinkedHashMap<>();
        properties.profiles().forEach((profileId, definition) -> {
            if (values.containsKey(profileId)) {
                throw new IllegalArgumentException("Duplicate model profile: " + profileId);
            }
            values.put(profileId, toProfile(profileId, definition));
        });
        values.forEach((profileId, profile) -> validateFallbackProfile(profileId, profile, values));
        this.profiles = Map.copyOf(values);
    }

    /**
     * Resolves the logical profile requested by a server-created model request.
     *
     * @param profileId logical profile name
     * @return immutable profile
     */
    public ModelProfile requireProfile(String profileId) {
        ModelProfile profile = profiles.get(profileId);
        if (profile == null) {
            throw new ModelGatewayException(Code.MODEL_PROFILE_NOT_FOUND, "Model profile is not configured");
        }
        return profile;
    }

    /**
     * Returns configured profiles for diagnostics that do not include credentials.
     *
     * @return immutable profiles by logical identifier
     */
    public Map<String, ModelProfile> profiles() {
        return profiles;
    }

    private ModelProfile toProfile(String profileId, ModelProfilesProperties.ProfileDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("Model profile definition must not be null");
        }
        ModelProfilesProperties.RetryDefinition retry = definition.retry();
        return new ModelProfile(
                profileId,
                ModelProfile.Provider.fromConfiguration(definition.provider()),
                definition.modelName(),
                definition.temperature(),
                definition.timeout() == null ? DEFAULT_TIMEOUT : definition.timeout(),
                definition.maxTokens() == 0 ? DEFAULT_MAX_TOKENS : definition.maxTokens(),
                new RetryPolicy(
                        retry == null ? 0 : retry.maxRetries(),
                        retry == null || retry.initialBackoff() == null ? DEFAULT_BACKOFF : retry.initialBackoff()),
                definition.fallbackProfile(),
                definition.streamEnabled() == null || definition.streamEnabled());
    }

    private static void validateFallbackProfile(
            String profileId, ModelProfile profile, Map<String, ModelProfile> profiles) {
        String fallbackProfileId = profile.fallbackProfileId();
        if (fallbackProfileId == null) {
            return;
        }
        if (profileId.equals(fallbackProfileId)) {
            throw new IllegalArgumentException("Model profile must not fall back to itself: " + profileId);
        }
        if (!profiles.containsKey(fallbackProfileId)) {
            throw new IllegalArgumentException("Fallback model profile is not configured: " + fallbackProfileId);
        }
    }
}
