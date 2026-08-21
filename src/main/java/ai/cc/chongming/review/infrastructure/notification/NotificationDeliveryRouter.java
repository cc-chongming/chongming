package ai.cc.chongming.review.infrastructure.notification;

import ai.cc.chongming.review.application.NotificationDeliveryException;
import ai.cc.chongming.review.application.NotificationDeliveryPort;
import ai.cc.chongming.review.domain.model.NotificationCommand;
import ai.cc.chongming.review.domain.model.NotificationDeliveryReceipt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

/**
 * [AIREVIEW-PLAN-011#1.6] Routes an outbox command to the delivery adapter matching its channel.
 * Unknown or disabled channels fail closed without touching review facts.
 *
 * @author wangli
 */
@Primary
@Component
public class NotificationDeliveryRouter implements NotificationDeliveryPort {

    public static final String MAIL_CHANNEL = "smtp-mail";
    public static final String CHAOXING_CHANNEL = "chaoxing";

    private final ObjectProvider<SmtpMailNotificationAdapter> mailAdapterProvider;
    private final ObjectProvider<LearningPlatformMcpAdapter> learningPlatformAdapterProvider;
    private final ObjectProvider<ChaoxingNotificationAdapter> chaoxingAdapterProvider;

    public NotificationDeliveryRouter(
            ObjectProvider<SmtpMailNotificationAdapter> mailAdapterProvider,
            ObjectProvider<LearningPlatformMcpAdapter> learningPlatformAdapterProvider,
            ObjectProvider<ChaoxingNotificationAdapter> chaoxingAdapterProvider) {
        this.mailAdapterProvider = Objects.requireNonNull(mailAdapterProvider, "mailAdapterProvider must not be null");
        this.learningPlatformAdapterProvider = Objects.requireNonNull(
                learningPlatformAdapterProvider, "learningPlatformAdapterProvider must not be null");
        this.chaoxingAdapterProvider = Objects.requireNonNull(
                chaoxingAdapterProvider, "chaoxingAdapterProvider must not be null");
    }

    @Override
    public NotificationDeliveryReceipt deliver(NotificationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (MAIL_CHANNEL.equalsIgnoreCase(command.channel())) {
            SmtpMailNotificationAdapter mailAdapter = mailAdapterProvider.getIfAvailable();
            if (mailAdapter == null) {
                throw new NotificationDeliveryException("MAIL_DISABLED", false,
                        "SMTP mail channel is disabled by configuration (review.notification.mail-enabled)");
            }
            return mailAdapter.deliver(command);
        }
        if (CHAOXING_CHANNEL.equalsIgnoreCase(command.channel())) {
            ChaoxingNotificationAdapter chaoxingAdapter = chaoxingAdapterProvider.getIfAvailable();
            if (chaoxingAdapter == null) {
                throw new NotificationDeliveryException("CHAOXING_DISABLED", false,
                        "Chaoxing channel is disabled by configuration (review.notification.chaoxing.enabled)");
            }
            return chaoxingAdapter.deliver(command);
        }
        LearningPlatformMcpAdapter learningPlatformAdapter = learningPlatformAdapterProvider.getIfAvailable();
        if (learningPlatformAdapter == null) {
            throw new NotificationDeliveryException("CHANNEL_UNAVAILABLE", false,
                    "No delivery adapter is configured for channel: " + command.channel());
        }
        return learningPlatformAdapter.deliver(command);
    }

    /** Normalized channel name used when enqueueing mail notifications. */
    public static String mailChannel() {
        return MAIL_CHANNEL.toLowerCase(Locale.ROOT);
    }
}
