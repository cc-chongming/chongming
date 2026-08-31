package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.GateDecision;
import ai.cc.chongming.review.domain.repository.ReviewDebateStore;
import ai.cc.chongming.review.infrastructure.persistence.mapper.DebatePersistenceMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import static ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import static ai.cc.chongming.review.domain.model.ReviewTypes.ClaimPosition;
import static ai.cc.chongming.review.domain.model.ReviewTypes.ClaimSeverity;
import static ai.cc.chongming.review.domain.model.ReviewTypes.ClaimStatus;
import static ai.cc.chongming.review.domain.model.ReviewTypes.DebateTopicStatus;
import static ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurn;
import static ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurnType;
import static ai.cc.chongming.review.domain.model.ReviewTypes.DecisionActor;
import static ai.cc.chongming.review.domain.model.ReviewTypes.DecisionStatus;
import static ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceId;
import static ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceReference;
import static ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import static ai.cc.chongming.review.domain.model.ReviewTypes.JudgeDecision;
import static ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import static ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import static ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import static ai.cc.chongming.review.domain.model.ReviewTypes.TurnId;

/**
 * [AIREVIEW-PLAN-010#1.3] Durable debate/conflict store used whenever review persistence is enabled,
 * so claims, topics, turns, judge decisions and the AI Gate draft survive a restart. Mutable topic
 * state is re-saved on every mutation (the in-memory store relies on the shared object reference).
 *
 * @author zyj
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")
public class MyBatisReviewDebateStore implements ReviewDebateStore {

    private static final TypeReference<List<EvidenceReference>> EVIDENCE_REFERENCES =
            new TypeReference<>() {
            };
    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() {
            };

    private final DebatePersistenceMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisReviewDebateStore(DebatePersistenceMapper mapper, ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    @Transactional
    public void saveClaim(Claim claim) {
        Objects.requireNonNull(claim, "claim must not be null");
        mapper.upsertClaim(new DebatePersistenceMapper.ClaimRow(
                claim.claimId().value().toString(),
                claim.reviewId().value().toString(),
                claim.roleType().name(),
                claim.subjectKey(),
                claim.severity().name(),
                claim.position().name(),
                claim.status().name(),
                claim.statement(),
                claim.reasonSummary(),
                write(claim.evidenceReferences()),
                java.time.LocalDateTime.now(ZoneOffset.UTC)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Claim> findClaim(ReviewId reviewId, ClaimId claimId) {
        return Optional.ofNullable(mapper.findClaim(reviewId.value().toString(), claimId.value().toString()))
                .map(this::toClaim);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Claim> findClaims(ReviewId reviewId) {
        return mapper.findClaims(reviewId.value().toString()).stream().map(this::toClaim).toList();
    }

    @Override
    @Transactional
    public void saveTopic(DebateTopic topic) {
        mapper.upsertTopic(toTopicRow(Objects.requireNonNull(topic, "topic must not be null")));
    }

    @Override
    @Transactional
    public void saveTopics(List<DebateTopic> topics) {
        Objects.requireNonNull(topics, "topics must not be null");
        if (topics.isEmpty()) {
            return;
        }
        // [AIREVIEW-PLAN-078#3] Assign each brand-new row the next registration sequence in batch
        // order; existing rows keep their original sequence so a snapshot re-save does not renumber.
        String reviewId = topics.get(0).reviewId().value().toString();
        Integer nextTopicSeq = null;
        List<DebatePersistenceMapper.TopicRow> rows = new ArrayList<>();
        for (DebateTopic topic : topics) {
            Objects.requireNonNull(topic, "topic must not be null");
            DebatePersistenceMapper.TopicRow existing =
                    mapper.findTopic(reviewId, topic.id().value().toString());
            int topicSeq;
            if (existing != null) {
                topicSeq = existing.topicSeq() == null ? 0 : existing.topicSeq();
            } else {
                if (nextTopicSeq == null) {
                    nextTopicSeq = mapper.maxTopicSeq(reviewId);
                }
                nextTopicSeq = nextTopicSeq + 1;
                topicSeq = nextTopicSeq;
            }
            rows.add(toTopicRow(topic, topicSeq));
        }
        mapper.upsertTopics(rows);
    }

    private DebatePersistenceMapper.TopicRow toTopicRow(DebateTopic topic) {
        // [AIREVIEW-PLAN-078#2] Single-row snapshot updates never touch topic_seq in the
        // ON DUPLICATE KEY UPDATE list, so the placeholder sequence is irrelevant for existing rows.
        return toTopicRow(topic, 0);
    }

    private DebatePersistenceMapper.TopicRow toTopicRow(DebateTopic topic, int topicSeq) {
        return new DebatePersistenceMapper.TopicRow(
                topic.id().value().toString(),
                topic.reviewId().value().toString(),
                topic.subjectKey(),
                topic.publicTitle(),
                writeStringList(topic.claimIds().stream().map(id -> id.value().toString()).toList()),
                topic.status().name(),
                topic.currentRound(),
                topic.resolution(),
                topic.closedAt() == null ? null : topic.closedAt().atOffset(ZoneOffset.UTC).toLocalDateTime(),
                java.time.LocalDateTime.now(ZoneOffset.UTC),
                topicSeq);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DebateTopic> findTopic(ReviewId reviewId, TopicId topicId) {
        return Optional.ofNullable(mapper.findTopic(reviewId.value().toString(), topicId.value().toString()))
                .map(this::toTopic);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DebateTopic> findTopics(ReviewId reviewId) {
        return mapper.findTopics(reviewId.value().toString()).stream().map(this::toTopic).toList();
    }

    @Override
    @Transactional
    public void saveTurn(ReviewId reviewId, DebateTurn turn) {
        Objects.requireNonNull(turn, "turn must not be null");
        mapper.insertTurn(new DebatePersistenceMapper.TurnRow(
                turn.turnId().value().toString(),
                turn.topicId().value().toString(),
                turn.round(),
                turn.actorRole().name(),
                turn.targetRole() == null ? null : turn.targetRole().name(),
                turn.turnType().name(),
                turn.targetClaimId() == null ? null : turn.targetClaimId().value().toString(),
                turn.targetTurnId() == null ? null : turn.targetTurnId().value().toString(),
                turn.publicContent(),
                writeStringList(turn.evidenceIds().stream().map(id -> id.value().toString()).toList()),
                turn.stanceBefore() == null ? null : turn.stanceBefore().name(),
                turn.stanceAfter() == null ? null : turn.stanceAfter().name(),
                turn.createdAt().atOffset(ZoneOffset.UTC).toLocalDateTime()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DebateTurn> findTurn(ReviewId reviewId, TurnId turnId) {
        return Optional.ofNullable(mapper.findTurn(turnId.value().toString())).map(this::toTurn);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DebateTurn> findTurns(ReviewId reviewId, TopicId topicId) {
        return mapper.findTurnsByTopic(topicId.value().toString()).stream().map(this::toTurn).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DebateTurn> findTurns(ReviewId reviewId) {
        return mapper.findTurnsByReview(reviewId.value().toString()).stream().map(this::toTurn).toList();
    }

    @Override
    @Transactional
    public void saveJudgeDecision(ReviewId reviewId, JudgeDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        mapper.upsertJudgeDecision(new DebatePersistenceMapper.JudgeDecisionRow(
                decision.topicId().value().toString(),
                decision.result().name(),
                decision.publicReasonSummary(),
                writeClaimIds(decision.acceptedClaimIds()),
                writeClaimIds(decision.rejectedClaimIds()),
                decision.createdAt().atOffset(ZoneOffset.UTC).toLocalDateTime()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JudgeDecision> findJudgeDecision(ReviewId reviewId, TopicId topicId) {
        return Optional.ofNullable(mapper.findJudgeDecision(topicId.value().toString())).map(this::toJudgeDecision);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<TopicId, JudgeDecision> findJudgeDecisions(ReviewId reviewId) {
        Map<TopicId, JudgeDecision> decisions = new java.util.LinkedHashMap<>();
        for (DebatePersistenceMapper.JudgeDecisionRow row : mapper.findJudgeDecisions(reviewId.value().toString())) {
            decisions.put(new TopicId(UUID.fromString(row.topicId())), toJudgeDecision(row));
        }
        return Map.copyOf(decisions);
    }

    @Override
    @Transactional
    public void saveGateDraft(GateDecision decision) {
        Objects.requireNonNull(decision, "decision must not be null");
        mapper.upsertGateDraft(new DebatePersistenceMapper.GateDraftRow(
                decision.reviewId().value().toString(),
                decision.result().name(),
                decision.status().name(),
                decision.actor().name(),
                decision.publicReasonSummary(),
                decision.decidedAt().atOffset(ZoneOffset.UTC).toLocalDateTime()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GateDecision> findGateDraft(ReviewId reviewId) {
        return Optional.ofNullable(mapper.findGateDraft(reviewId.value().toString())).map(row -> new GateDecision(
                new ReviewId(UUID.fromString(row.reviewId())),
                GateResult.valueOf(row.result()),
                DecisionStatus.valueOf(row.decisionStatus()),
                DecisionActor.valueOf(row.decisionActor()),
                row.publicReasonSummary(),
                row.decidedAt().toInstant(ZoneOffset.UTC)));
    }

    private Claim toClaim(DebatePersistenceMapper.ClaimRow row) {
        return new Claim(
                new ClaimId(UUID.fromString(row.claimId())),
                new ReviewId(UUID.fromString(row.reviewId())),
                RoleType.valueOf(row.roleType()),
                row.subjectKey(),
                ClaimSeverity.valueOf(row.severity()),
                ClaimPosition.valueOf(row.position()),
                row.statement(),
                row.reasonSummary(),
                readEvidenceReferences(row.evidenceJson()),
                ClaimStatus.valueOf(row.status()));
    }

    private DebateTopic toTopic(DebatePersistenceMapper.TopicRow row) {
        return DebateTopic.restore(
                new TopicId(UUID.fromString(row.topicId())),
                new ReviewId(UUID.fromString(row.reviewId())),
                row.subjectKey(),
                readClaimIds(row.claimIdsJson()),
                row.publicTitle(),
                DebateTopicStatus.valueOf(row.status()),
                row.currentRound(),
                List.of(),
                row.resolution(),
                row.closedAt() == null ? null : row.closedAt().toInstant(ZoneOffset.UTC));
    }

    private DebateTurn toTurn(DebatePersistenceMapper.TurnRow row) {
        return new DebateTurn(
                new TurnId(UUID.fromString(row.turnId())),
                new TopicId(UUID.fromString(row.topicId())),
                row.round(),
                RoleType.valueOf(row.actorRole()),
                row.targetRole() == null ? null : RoleType.valueOf(row.targetRole()),
                DebateTurnType.valueOf(row.turnType()),
                row.targetClaimId() == null ? null : new ClaimId(UUID.fromString(row.targetClaimId())),
                row.targetTurnId() == null ? null : new TurnId(UUID.fromString(row.targetTurnId())),
                row.publicContent(),
                readEvidenceIds(row.evidenceIdsJson()),
                row.stanceBefore() == null ? null : ClaimPosition.valueOf(row.stanceBefore()),
                row.stanceAfter() == null ? null : ClaimPosition.valueOf(row.stanceAfter()),
                row.createdAt().toInstant(ZoneOffset.UTC));
    }

    private JudgeDecision toJudgeDecision(DebatePersistenceMapper.JudgeDecisionRow row) {
        return new JudgeDecision(
                new TopicId(UUID.fromString(row.topicId())),
                GateResult.valueOf(row.result()),
                row.publicReasonSummary(),
                readClaimIds(row.acceptedClaimIdsJson()),
                readClaimIds(row.rejectedClaimIdsJson()),
                row.decidedAt().toInstant(ZoneOffset.UTC));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("debate data could not be serialized", exception);
        }
    }

    private String writeStringList(List<String> values) {
        // review_debate_topic.claim_ids_json is NOT NULL (V16); an empty list serializes as "[]",
        // which the read side already normalizes back to an empty list.
        return values.isEmpty() ? "[]" : write(values);
    }

    private String writeClaimIds(List<ClaimId> claimIds) {
        if (claimIds.isEmpty()) {
            return null;
        }
        return writeStringList(claimIds.stream().map(id -> id.value().toString()).toList());
    }

    private List<EvidenceReference> readEvidenceReferences(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, EVIDENCE_REFERENCES);
        } catch (Exception exception) {
            throw new IllegalStateException("claim evidence references could not be parsed", exception);
        }
    }

    private List<ClaimId> readClaimIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST).stream()
                    .map(value -> new ClaimId(UUID.fromString(value)))
                    .toList();
        } catch (Exception exception) {
            throw new IllegalStateException("claim id list could not be parsed", exception);
        }
    }

    private List<EvidenceId> readEvidenceIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST).stream()
                    .map(value -> new EvidenceId(UUID.fromString(value)))
                    .toList();
        } catch (Exception exception) {
            throw new IllegalStateException("evidence id list could not be parsed", exception);
        }
    }
}
