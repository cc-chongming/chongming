package ai.cc.chongming.review.infrastructure.notification;

import ai.cc.chongming.review.application.NotificationDeliveryException;
import ai.cc.chongming.review.application.NotificationDeliveryPort;
import ai.cc.chongming.review.config.NotificationOutboxProperties;
import ai.cc.chongming.review.domain.model.NotificationCommand;
import ai.cc.chongming.review.domain.model.NotificationDeliveryReceipt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [AIREVIEW-PLAN-011#1.6] Maps a domain notification command to a deployment-provided, verified MCP client.
 *
 * <p>The repository intentionally has no direct HTTP/MCP call: tool name, remote JSON schema and authentication
 * fields have not been supplied as an authoritative artifact. Enabling this adapter without such a client fails
 * closed instead of guessing a remote request.
 *
 * @author wangli
 */
@Component
public class LearningPlatformMcpAdapter implements NotificationDeliveryPort {

    private final NotificationOutboxProperties properties;
    private final ObjectProvider<LearningPlatformMcpClient> clientProvider;

    public LearningPlatformMcpAdapter(
            NotificationOutboxProperties properties,
            ObjectProvider<LearningPlatformMcpClient> clientProvider) {
        this.properties = properties;
        this.clientProvider = clientProvider;
    }

    @Override
    public NotificationDeliveryReceipt deliver(NotificationCommand command) {
        if (!properties.mcpEnabled()) {
            throw new NotificationDeliveryException("MCP_DISABLED", false,
                    "Learning Platform MCP is disabled by configuration");
        }
        if (System.getenv(properties.credentialEnvironmentVariable()) == null
                || System.getenv(properties.credentialEnvironmentVariable()).isBlank()) {
            throw new NotificationDeliveryException("MCP_CREDENTIAL_UNAVAILABLE", false,
                    "Learning Platform MCP credential is not available from its configured environment variable");
        }
        LearningPlatformMcpClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new NotificationDeliveryException("MCP_CLIENT_UNAVAILABLE", false,
                    "No verified Learning Platform MCP client has been configured");
        }
        try {
            return client.deliver(toRequest(command));
        } catch (NotificationDeliveryException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new NotificationDeliveryException("MCP_TRANSPORT_ERROR", true,
                    "Learning Platform MCP invocation failed", exception);
        }
    }

    private LearningPlatformMcpRequest toRequest(NotificationCommand command) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("reviewId", command.reviewId().value().toString());
        attributes.put("gateVersion", Long.toString(command.gateVersion()));
        attributes.put("channel", command.channel());
        attributes.put("destination", command.destination());
        attributes.put("result", command.result().name());
        attributes.put("reason", command.reason());
        attributes.put("reportUrl", command.reportUrl());
        return new LearningPlatformMcpRequest(command.idempotencyKey(), attributes, command.conditions());
    }
}
