package ai.cc.chongming.review.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * [AIREVIEW-PLAN-030] Chaoxing (学习通) notice channel settings. Safe-by-default: the channel is
 * disabled unless explicitly enabled, so the QQ-mail channel remains the active path until the
 * Chaoxing endpoint, sender ({@code puid}/{@code pcode}) and the signing {@code token}/{@code key}
 * used by {@code fillParams_encnew} are configured.
 *
 * @author wangli
 */
@ConfigurationProperties("review.notification.chaoxing")
public record ChaoxingNotificationProperties(
        boolean enabled,
        String api,
        String detailUrl,
        String puid,
        String pcode,
        String token,
        String key) {

    public ChaoxingNotificationProperties {
        api = api == null ? "" : api.trim();
        detailUrl = detailUrl == null ? "" : detailUrl.trim();
        puid = puid == null ? "" : puid.trim();
        pcode = pcode == null ? "" : pcode.trim();
        token = token == null ? "" : token.trim();
        key = key == null ? "" : key.trim();
    }
}
