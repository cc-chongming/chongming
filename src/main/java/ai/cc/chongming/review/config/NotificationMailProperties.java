package ai.cc.chongming.review.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * [AIREVIEW-PLAN-011#1.6] QQ/SMTP mail channel settings. Deployments should keep credentials in
 * environment variables; the optional authCode only exists so a local profile can bake the QQ
 * authorization code into application-local.yml, matching the existing local-secret convention.
 *
 * @author wangli
 */
@ConfigurationProperties("review.notification.mail")
public record NotificationMailProperties(
        String host,
        int port,
        String sender,
        String credentialEnvironmentVariable,
        String subjectPrefix,
        String authCode) {

    public NotificationMailProperties(
            String host, int port, String sender, String credentialEnvironmentVariable, String subjectPrefix) {
        this(host, port, sender, credentialEnvironmentVariable, subjectPrefix, null);
    }

    @ConstructorBinding
    public NotificationMailProperties {
        host = host == null || host.isBlank() ? "smtp.qq.com" : host;
        port = port == 0 ? 465 : port;
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("mail port must be between 1 and 65535");
        }
        sender = sender == null ? "" : sender;
        credentialEnvironmentVariable = credentialEnvironmentVariable == null || credentialEnvironmentVariable.isBlank()
                ? "REVIEW_QQ_MAIL_AUTH_CODE"
                : credentialEnvironmentVariable;
        subjectPrefix = subjectPrefix == null || subjectPrefix.isBlank() ? "【重明需求评审】" : subjectPrefix;
        authCode = authCode == null ? "" : authCode;
    }
}
