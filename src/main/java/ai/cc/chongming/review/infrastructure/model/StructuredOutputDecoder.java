package ai.cc.chongming.review.infrastructure.model;

import ai.cc.chongming.review.domain.gateway.StructuredOutputs;
import ai.cc.chongming.review.domain.gateway.StructuredOutputs.JudgeDecisionOutput;
import ai.cc.chongming.review.domain.gateway.StructuredOutputs.Kind;
import ai.cc.chongming.review.domain.gateway.StructuredOutputs.PlanOutput;
import ai.cc.chongming.review.domain.gateway.StructuredOutputs.RoleAssessmentOutput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import org.springframework.stereotype.Component;

import static ai.cc.chongming.review.infrastructure.model.StructuredOutputException.Code;

/**
 * Strictly decodes model JSON once, with at most one caller-supplied repair attempt.
 *
 * @author wangli
 */
@Component
public class StructuredOutputDecoder {

    private final ObjectMapper objectMapper;

    public StructuredOutputDecoder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null")
                .copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * Decodes a Plan output and records whether the one permitted repair path was used.
     */
    public Decoded<PlanOutput> decodePlan(String rawJson, Repairer repairer) {
        return decode(Kind.PLAN, rawJson, PlanOutput.class, repairer);
    }

    /**
     * Decodes a role assessment without creating claims or trusting evidence IDs as authoritative.
     */
    public Decoded<RoleAssessmentOutput> decodeRoleAssessment(String rawJson, Repairer repairer) {
        return decode(Kind.ROLE_ASSESSMENT, rawJson, RoleAssessmentOutput.class, repairer);
    }

    /**
     * Decodes a judge proposal without creating a final Gate decision.
     */
    public Decoded<JudgeDecisionOutput> decodeJudgeDecision(String rawJson, Repairer repairer) {
        return decode(Kind.JUDGE_DECISION, rawJson, JudgeDecisionOutput.class, repairer);
    }

    private <T> Decoded<T> decode(Kind kind, String rawJson, Class<T> type, Repairer repairer) {
        try {
            return new Decoded<>(decodeOnce(rawJson, type), false, sha256(rawJson));
        } catch (StructuredOutputException firstFailure) {
            if (repairer == null) {
                throw firstFailure;
            }
            String repaired;
            try {
                repaired = repairer.repair(kind, rawJson, firstFailure.code());
            } catch (RuntimeException exception) {
                throw new StructuredOutputException(Code.REPAIR_FAILED, "Structured output repair failed", exception);
            }
            if (repaired == null || repaired.isBlank()) {
                throw new StructuredOutputException(Code.REPAIR_FAILED, "Structured output repair returned no JSON");
            }
            try {
                return new Decoded<>(decodeOnce(repaired, type), true, sha256(repaired));
            } catch (StructuredOutputException repairFailure) {
                throw new StructuredOutputException(Code.REPAIR_FAILED, "Structured output remains invalid after one repair", repairFailure);
            }
        }
    }

    private <T> T decodeOnce(String rawJson, Class<T> type) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new StructuredOutputException(Code.MALFORMED_JSON, "Structured output is blank");
        }
        try {
            return objectMapper.readValue(rawJson, type);
        } catch (JsonProcessingException exception) {
            throw new StructuredOutputException(Code.MALFORMED_JSON, "Structured output is not valid for its contract", exception);
        } catch (IllegalArgumentException exception) {
            throw new StructuredOutputException(Code.SCHEMA_VIOLATION, "Structured output violates its contract", exception);
        }
    }

    private String sha256(String value) {
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
     * One explicitly bounded JSON repair callback, normally backed by a dedicated model repair prompt.
     *
     * @author wangli
     */
    @FunctionalInterface
    public interface Repairer {

        String repair(Kind kind, String invalidJson, Code failureCode);
    }

    /**
     * Decoded contract value plus the final JSON hash; raw model text is intentionally not retained here.
     *
     * @author wangli
     */
    public record Decoded<T>(T value, boolean repaired, String contentHash) {
    }
}
