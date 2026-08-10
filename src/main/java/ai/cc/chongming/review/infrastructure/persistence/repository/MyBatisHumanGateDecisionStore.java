package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.model.HumanGateDecision;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.HumanGateDecisionStore;
import ai.cc.chongming.review.infrastructure.persistence.mapper.HumanGateDecisionPersistenceMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * [AIREVIEW-PLAN-011#1.3] Durable human Gate store used whenever review persistence is enabled, so the
 * final Gate history survives a restart. The in-memory store is only active when persistence is disabled.
 *
 * @author wangli
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")
public class MyBatisHumanGateDecisionStore implements HumanGateDecisionStore {

    private static final TypeReference<List<String>> CONDITIONS_TYPE = new TypeReference<>() {
    };

    private final HumanGateDecisionPersistenceMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisHumanGateDecisionStore(HumanGateDecisionPersistenceMapper mapper, ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    @Transactional
    public void append(HumanGateDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        String conditionsJson;
        try {
            conditionsJson = decision.conditions().isEmpty() ? null : objectMapper.writeValueAsString(decision.conditions());
        } catch (Exception exception) {
            throw new IllegalStateException("human Gate conditions could not be serialized", exception);
        }
        if (mapper.insert(new HumanGateDecisionPersistenceMapper.HumanGateDecisionRow(
                UUID.randomUUID().toString(),
                decision.reviewId().value().toString(),
                decision.gateVersion(),
                decision.result().name(),
                decision.reason(),
                conditionsJson,
                decision.overrideReason(),
                decision.reviewerId(),
                decision.supersedesVersion(),
                decision.decidedAt().atOffset(ZoneOffset.UTC).toLocalDateTime())) != 1) {
            throw new IllegalStateException("human Gate decision was not persisted");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<HumanGateDecision> findLatest(ReviewId reviewId) {
        return Optional.ofNullable(mapper.findLatest(reviewId.value().toString())).map(this::toDecision);
    }

    @Override
    @Transactional(readOnly = true)
    public List<HumanGateDecision> findVersions(ReviewId reviewId) {
        return mapper.findVersions(reviewId.value().toString()).stream().map(this::toDecision).toList();
    }

    private HumanGateDecision toDecision(HumanGateDecisionPersistenceMapper.HumanGateDecisionRow row) {
        List<String> conditions;
        try {
            conditions = row.conditionsJson() == null || row.conditionsJson().isBlank()
                    ? List.of()
                    : objectMapper.readValue(row.conditionsJson(), CONDITIONS_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("human Gate conditions could not be parsed", exception);
        }
        return new HumanGateDecision(
                new ReviewId(UUID.fromString(row.reviewId())),
                row.gateVersion(),
                GateResult.valueOf(row.gateResult()),
                row.reasonText(),
                conditions,
                row.overrideReason(),
                row.reviewerId(),
                row.supersedesVersion(),
                row.decidedAt().toInstant(ZoneOffset.UTC));
    }
}
