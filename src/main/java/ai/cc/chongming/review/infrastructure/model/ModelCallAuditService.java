package ai.cc.chongming.review.infrastructure.model;

import ai.cc.chongming.review.domain.gateway.ModelGateway;
import ai.cc.chongming.review.domain.gateway.ModelGatewayException.Code;
import ai.cc.chongming.review.domain.gateway.ModelProfile;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;

/**
 * Stores bounded, credential-free model-call audit metadata until MyBatis audit persistence is enabled.
 *
 * @author wangli
 */
@Service
public class ModelCallAuditService {

    private final List<ModelCallAudit> entries = new CopyOnWriteArrayList<>();

    /**
     * Records a successful call using hashes rather than prompts, provider credentials or hidden reasoning.
     */
    public void recordSuccess(ModelGateway.ModelRequest request, ModelProfile profile, ModelGateway.ModelResponse response) {
        entries.add(new ModelCallAudit(
                request.reviewId(),
                request.roleType().name(),
                profile.profileId(),
                profile.modelName(),
                request.promptVersion(),
                hash(request.systemInstruction() + "\n" + request.publicContext()),
                response.publicText().isBlank() ? null : hash(response.publicText()),
                response.latency(),
                response.usage(),
                response.attempts(),
                null,
                request.traceId(),
                Instant.now()));
    }

    /**
     * Records a stable failure category without storing error payloads, headers or credentials.
     */
    public void recordFailure(
            ModelGateway.ModelRequest request, ModelProfile profile, Code failureCode, int attempts) {
        entries.add(new ModelCallAudit(
                request.reviewId(),
                request.roleType().name(),
                profile == null ? request.profileId() : profile.profileId(),
                profile == null ? null : profile.modelName(),
                request.promptVersion(),
                hash(request.systemInstruction() + "\n" + request.publicContext()),
                null,
                Duration.ZERO,
                new ModelGateway.Usage(0, 0, 0),
                attempts,
                Objects.requireNonNull(failureCode, "failureCode must not be null").name(),
                request.traceId(),
                Instant.now()));
    }

    /**
     * Returns audit entries for one review without exposing another review's metadata.
     */
    public List<ModelCallAudit> findByReview(ReviewId reviewId) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        return entries.stream().filter(entry -> entry.reviewId().equals(reviewId)).toList();
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte valueByte : digest) {
                result.append(String.format("%02x", valueByte));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * Credential-free audit projection for future persistence and operational diagnostics.
     *
     * @author wangli
     */
    public record ModelCallAudit(
            ReviewId reviewId,
            String roleType,
            String profileId,
            String modelName,
            String promptVersion,
            String inputHash,
            String outputHash,
            Duration latency,
            ModelGateway.Usage usage,
            int attempts,
            String failureCode,
            String traceId,
            Instant occurredAt) {
    }
}
