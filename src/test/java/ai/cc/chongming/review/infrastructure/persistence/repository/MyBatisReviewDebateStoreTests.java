package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.GateDecision;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimPosition;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimSeverity;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTopicStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurn;
import ai.cc.chongming.review.domain.model.ReviewTypes.DebateTurnType;
import ai.cc.chongming.review.domain.model.ReviewTypes.DecisionActor;
import ai.cc.chongming.review.domain.model.ReviewTypes.DecisionStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceId;
import ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceReference;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.JudgeDecision;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import ai.cc.chongming.review.domain.model.ReviewTypes.TurnId;
import ai.cc.chongming.review.infrastructure.persistence.mapper.DebatePersistenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [AIREVIEW-PLAN-010#1.3] Verifies the durable debate store round-trips every field so the conflict,
 * debate, judge and Gate-draft projections survive a restart.
 *
 * @author wangli
 */
class MyBatisReviewDebateStoreTests {

    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    @Test
    void roundTripsClaimsWithEvidenceReferences() {
        MyBatisReviewDebateStore store = newStore();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ClaimId claimId = new ClaimId(UUID.randomUUID());
        EvidenceId evidenceId = new EvidenceId(UUID.randomUUID());
        Claim claim = new Claim(
                claimId,
                reviewId,
                RoleType.FRONTEND,
                "增量展示可行",
                ClaimSeverity.P2,
                ClaimPosition.SUPPORT,
                "前端已有 DiffViewer，增量数据展示无技术障碍。",
                "组件成熟。",
                List.of(new EvidenceReference(evidenceId, "snap-1", "src/view/DiffViewer.vue", 42, "hash-1")),
                ClaimStatus.SUBMITTED);

        store.saveClaim(claim);

        Claim reloaded = store.findClaim(reviewId, claimId).orElseThrow();
        assertThat(reloaded.reviewId()).isEqualTo(reviewId);
        assertThat(reloaded.roleType()).isEqualTo(RoleType.FRONTEND);
        assertThat(reloaded.subjectKey()).isEqualTo("增量展示可行");
        assertThat(reloaded.severity()).isEqualTo(ClaimSeverity.P2);
        assertThat(reloaded.position()).isEqualTo(ClaimPosition.SUPPORT);
        assertThat(reloaded.statement()).isEqualTo("前端已有 DiffViewer，增量数据展示无技术障碍。");
        assertThat(reloaded.reasonSummary()).isEqualTo("组件成熟。");
        assertThat(reloaded.status()).isEqualTo(ClaimStatus.SUBMITTED);
        assertThat(reloaded.evidenceReferences()).hasSize(1);
        assertThat(reloaded.evidenceReferences().get(0).evidenceId()).isEqualTo(evidenceId);
        assertThat(reloaded.evidenceReferences().get(0).snapshotId()).isEqualTo("snap-1");
        assertThat(reloaded.evidenceReferences().get(0).relativePath()).isEqualTo("src/view/DiffViewer.vue");
        assertThat(reloaded.evidenceReferences().get(0).lineNumber()).isEqualTo(42);
        assertThat(reloaded.evidenceReferences().get(0).snippetHash()).isEqualTo("hash-1");
        assertThat(store.findClaims(reviewId)).hasSize(1);
    }

    @Test
    void roundTripsTerminalTopicWithTurns() {
        MyBatisReviewDebateStore store = newStore();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        TopicId topicId = new TopicId(UUID.randomUUID());
        ClaimId claimId = new ClaimId(UUID.randomUUID());
        TurnId turnId = new TurnId(UUID.randomUUID());
        EvidenceId evidenceId = new EvidenceId(UUID.randomUUID());
        DebateTurn challenge = new DebateTurn(
                turnId,
                topicId,
                1,
                RoleType.PRODUCT,
                RoleType.FRONTEND,
                DebateTurnType.CHALLENGE,
                claimId,
                null,
                "该能力需要管理员权限才可验证。",
                List.of(evidenceId),
                null,
                null,
                NOW);
        DebateTopic topic = DebateTopic.restore(
                topicId,
                reviewId,
                "增量展示权限",
                List.of(claimId),
                DebateTopicStatus.RESOLVED,
                2,
                List.of(challenge),
                "验证通过，无需权限。",
                NOW.plusSeconds(120));

        store.saveTopic(topic);
        store.saveTurn(reviewId, challenge);

        DebateTopic reloaded = store.findTopic(reviewId, topicId).orElseThrow();
        assertThat(reloaded.reviewId()).isEqualTo(reviewId);
        assertThat(reloaded.subjectKey()).isEqualTo("增量展示权限");
        assertThat(reloaded.claimIds()).containsExactly(claimId);
        assertThat(reloaded.status()).isEqualTo(DebateTopicStatus.RESOLVED);
        assertThat(reloaded.currentRound()).isEqualTo(2);
        assertThat(reloaded.resolution()).isEqualTo("验证通过，无需权限。");
        assertThat(reloaded.closedAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(reloaded.turns()).isEmpty(); // turns live in their own table
        assertThat(store.findTopics(reviewId)).hasSize(1);

        DebateTurn turn = store.findTurn(reviewId, turnId).orElseThrow();
        assertThat(turn.topicId()).isEqualTo(topicId);
        assertThat(turn.round()).isEqualTo(1);
        assertThat(turn.actorRole()).isEqualTo(RoleType.PRODUCT);
        assertThat(turn.targetRole()).isEqualTo(RoleType.FRONTEND);
        assertThat(turn.turnType()).isEqualTo(DebateTurnType.CHALLENGE);
        assertThat(turn.targetClaimId()).isEqualTo(claimId);
        assertThat(turn.publicContent()).isEqualTo("该能力需要管理员权限才可验证。");
        assertThat(turn.evidenceIds()).containsExactly(evidenceId);
        assertThat(store.findTurns(reviewId, topicId)).hasSize(1);
        assertThat(store.findTurns(reviewId)).hasSize(1);
    }

    @Test
    void roundTripsJudgeDecisionAndGateDraft() {
        MyBatisReviewDebateStore store = newStore();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        TopicId topicId = new TopicId(UUID.randomUUID());
        ClaimId accepted = new ClaimId(UUID.randomUUID());
        ClaimId rejected = new ClaimId(UUID.randomUUID());
        store.saveTopic(DebateTopic.restore(
                topicId,
                reviewId,
                "权限验证主题",
                List.of(accepted),
                DebateTopicStatus.RESOLVED,
                1,
                List.of(),
                "已终态。",
                NOW));
        JudgeDecision decision = new JudgeDecision(
                topicId,
                GateResult.CONDITIONAL,
                "补充权限说明后可通过。",
                List.of(accepted),
                List.of(rejected),
                NOW);

        store.saveJudgeDecision(reviewId, decision);

        JudgeDecision reloaded = store.findJudgeDecision(reviewId, topicId).orElseThrow();
        assertThat(reloaded.result()).isEqualTo(GateResult.CONDITIONAL);
        assertThat(reloaded.publicReasonSummary()).isEqualTo("补充权限说明后可通过。");
        assertThat(reloaded.acceptedClaimIds()).containsExactly(accepted);
        assertThat(reloaded.rejectedClaimIds()).containsExactly(rejected);
        assertThat(reloaded.createdAt()).isEqualTo(NOW);
        assertThat(store.findJudgeDecisions(reviewId)).containsKeys(topicId);

        GateDecision draft = new GateDecision(
                reviewId,
                GateResult.AI_PASS,
                DecisionStatus.DRAFT,
                DecisionActor.AI,
                "初审无阻塞项。",
                NOW);
        store.saveGateDraft(draft);

        GateDecision gate = store.findGateDraft(reviewId).orElseThrow();
        assertThat(gate.result()).isEqualTo(GateResult.AI_PASS);
        assertThat(gate.status()).isEqualTo(DecisionStatus.DRAFT);
        assertThat(gate.actor()).isEqualTo(DecisionActor.AI);
        assertThat(gate.publicReasonSummary()).isEqualTo("初审无阻塞项。");
        assertThat(gate.decidedAt()).isEqualTo(NOW);
    }

    private MyBatisReviewDebateStore newStore() {
        return new MyBatisReviewDebateStore(new FakeDebatePersistenceMapper(), new ObjectMapper());
    }

    /** @author wangli */
    private static final class FakeDebatePersistenceMapper implements DebatePersistenceMapper {

        private final Map<String, ClaimRow> claims = new LinkedHashMap<>();
        private final Map<String, TopicRow> topics = new LinkedHashMap<>();
        private final Map<String, TurnRow> turns = new LinkedHashMap<>();
        private final Map<String, JudgeDecisionRow> judgeDecisions = new LinkedHashMap<>();
        private final Map<String, GateDraftRow> gateDrafts = new LinkedHashMap<>();

        @Override
        public int upsertClaim(ClaimRow row) {
            claims.put(row.claimId(), row);
            return 1;
        }

        @Override
        public List<ClaimRow> findClaims(String reviewId) {
            return claims.values().stream().filter(row -> row.reviewId().equals(reviewId)).toList();
        }

        @Override
        public ClaimRow findClaim(String reviewId, String claimId) {
            return claims.get(claimId);
        }

        @Override
        public int upsertTopic(TopicRow row) {
            topics.put(row.topicId(), row);
            return 1;
        }

        @Override
        public List<TopicRow> findTopics(String reviewId) {
            return topics.values().stream().filter(row -> row.reviewId().equals(reviewId)).toList();
        }

        @Override
        public TopicRow findTopic(String reviewId, String topicId) {
            return topics.get(topicId);
        }

        @Override
        public int insertTurn(TurnRow row) {
            turns.put(row.turnId(), row);
            return 1;
        }

        @Override
        public TurnRow findTurn(String turnId) {
            return turns.get(turnId);
        }

        @Override
        public List<TurnRow> findTurnsByTopic(String topicId) {
            return turns.values().stream().filter(row -> row.topicId().equals(topicId)).toList();
        }

        @Override
        public List<TurnRow> findTurnsByReview(String reviewId) {
            List<TurnRow> rows = new ArrayList<>();
            for (TurnRow row : turns.values()) {
                TopicRow topic = topics.get(row.topicId());
                if (topic != null && topic.reviewId().equals(reviewId)) {
                    rows.add(row);
                }
            }
            return rows;
        }

        @Override
        public int upsertJudgeDecision(JudgeDecisionRow row) {
            judgeDecisions.put(row.topicId(), row);
            return 1;
        }

        @Override
        public JudgeDecisionRow findJudgeDecision(String topicId) {
            return judgeDecisions.get(topicId);
        }

        @Override
        public List<JudgeDecisionRow> findJudgeDecisions(String reviewId) {
            List<JudgeDecisionRow> rows = new ArrayList<>();
            for (JudgeDecisionRow row : judgeDecisions.values()) {
                TopicRow topic = topics.get(row.topicId());
                if (topic != null && topic.reviewId().equals(reviewId)) {
                    rows.add(row);
                }
            }
            return rows;
        }

        @Override
        public int upsertGateDraft(GateDraftRow row) {
            gateDrafts.put(row.reviewId(), row);
            return 1;
        }

        @Override
        public GateDraftRow findGateDraft(String reviewId) {
            return gateDrafts.get(reviewId);
        }
    }
}
