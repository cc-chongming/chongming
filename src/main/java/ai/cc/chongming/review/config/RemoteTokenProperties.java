package ai.cc.chongming.review.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * [AIREVIEW-PLAN-029] Encryption settings for requirement-supplied remote repository tokens.
 * The key is only injected through the {@code REVIEW_REMOTE_TOKEN_KEY} environment variable and
 * never appears in committed configuration.
 *
 * @author wangli
 */
@ConfigurationProperties(prefix = "review.remote-token")
public record RemoteTokenProperties(String key) {

    public RemoteTokenProperties {
        key = key == null || key.isBlank() ? null : key.trim();
    }
}
