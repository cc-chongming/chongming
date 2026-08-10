package ai.cc.chongming.review.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * [AIREVIEW-PLAN-010#1.3] Persists and reloads the debate/conflict store. Claims are immutable
 * first-write-wins; topics carry mutable state so upserting overwrites the latest snapshot; turns,
 * judge decisions and the Gate draft are also idempotent upserts.
 *
 * @author wangli
 */
@Mapper
public interface DebatePersistenceMapper {

    @Insert("""
            INSERT INTO review_debate_claim
                (claim_id, review_id, role_type, subject_key, severity, position, status,
                 statement_text, reason_summary, evidence_json, created_at)
            VALUES
                (#{row.claimId}, #{row.reviewId}, #{row.roleType}, #{row.subjectKey}, #{row.severity},
                 #{row.position}, #{row.status}, #{row.statement}, #{row.reasonSummary}, #{row.evidenceJson},
                 #{row.createdAt})
            ON DUPLICATE KEY UPDATE claim_id = VALUES(claim_id)
            """)
    int upsertClaim(@Param("row") ClaimRow row);

    @Select("""
            SELECT claim_id AS claimId, review_id AS reviewId, role_type AS roleType, subject_key AS subjectKey,
                   severity, position, status, statement_text AS statement, reason_summary AS reasonSummary,
                   evidence_json AS evidenceJson, created_at AS createdAt
            FROM review_debate_claim
            WHERE review_id = #{reviewId}
            ORDER BY claim_id
            """)
    List<ClaimRow> findClaims(@Param("reviewId") String reviewId);

    @Select("""
            SELECT claim_id AS claimId, review_id AS reviewId, role_type AS roleType, subject_key AS subjectKey,
                   severity, position, status, statement_text AS statement, reason_summary AS reasonSummary,
                   evidence_json AS evidenceJson, created_at AS createdAt
            FROM review_debate_claim
            WHERE review_id = #{reviewId} AND claim_id = #{claimId}
            """)
    ClaimRow findClaim(@Param("reviewId") String reviewId, @Param("claimId") String claimId);

    @Insert("""
            INSERT INTO review_debate_topic
                (topic_id, review_id, subject_key, claim_ids_json, status, current_round, resolution, closed_at, created_at)
            VALUES
                (#{row.topicId}, #{row.reviewId}, #{row.subjectKey}, #{row.claimIdsJson}, #{row.status},
                 #{row.currentRound}, #{row.resolution}, #{row.closedAt}, #{row.createdAt})
            ON DUPLICATE KEY UPDATE
                subject_key = VALUES(subject_key),
                claim_ids_json = VALUES(claim_ids_json),
                status = VALUES(status),
                current_round = VALUES(current_round),
                resolution = VALUES(resolution),
                closed_at = VALUES(closed_at)
            """)
    int upsertTopic(@Param("row") TopicRow row);

    @Select("""
            SELECT topic_id AS topicId, review_id AS reviewId, subject_key AS subjectKey,
                   claim_ids_json AS claimIdsJson, status, current_round AS currentRound,
                   resolution, closed_at AS closedAt, created_at AS createdAt
            FROM review_debate_topic
            WHERE review_id = #{reviewId}
            ORDER BY topic_id
            """)
    List<TopicRow> findTopics(@Param("reviewId") String reviewId);

    @Select("""
            SELECT topic_id AS topicId, review_id AS reviewId, subject_key AS subjectKey,
                   claim_ids_json AS claimIdsJson, status, current_round AS currentRound,
                   resolution, closed_at AS closedAt, created_at AS createdAt
            FROM review_debate_topic
            WHERE review_id = #{reviewId} AND topic_id = #{topicId}
            """)
    TopicRow findTopic(@Param("reviewId") String reviewId, @Param("topicId") String topicId);

    @Insert("""
            INSERT IGNORE INTO review_debate_turn
                (turn_id, topic_id, round_no, actor_role, target_role, turn_type, target_claim_id,
                 target_turn_id, public_content, evidence_ids_json, stance_before, stance_after, created_at)
            VALUES
                (#{row.turnId}, #{row.topicId}, #{row.round}, #{row.actorRole}, #{row.targetRole},
                 #{row.turnType}, #{row.targetClaimId}, #{row.targetTurnId}, #{row.publicContent},
                 #{row.evidenceIdsJson}, #{row.stanceBefore}, #{row.stanceAfter}, #{row.createdAt})
            """)
    int insertTurn(@Param("row") TurnRow row);

    @Select("""
            SELECT turn_id AS turnId, topic_id AS topicId, round_no AS round, actor_role AS actorRole,
                   target_role AS targetRole, turn_type AS turnType, target_claim_id AS targetClaimId,
                   target_turn_id AS targetTurnId, public_content AS publicContent,
                   evidence_ids_json AS evidenceIdsJson, stance_before AS stanceBefore,
                   stance_after AS stanceAfter, created_at AS createdAt
            FROM review_debate_turn
            WHERE turn_id = #{turnId}
            """)
    TurnRow findTurn(@Param("turnId") String turnId);

    @Select("""
            SELECT turn_id AS turnId, topic_id AS topicId, round_no AS round, actor_role AS actorRole,
                   target_role AS targetRole, turn_type AS turnType, target_claim_id AS targetClaimId,
                   target_turn_id AS targetTurnId, public_content AS publicContent,
                   evidence_ids_json AS evidenceIdsJson, stance_before AS stanceBefore,
                   stance_after AS stanceAfter, created_at AS createdAt
            FROM review_debate_turn
            WHERE topic_id = #{topicId}
            ORDER BY round_no, turn_id
            """)
    List<TurnRow> findTurnsByTopic(@Param("topicId") String topicId);

    @Select("""
            SELECT turn.turn_id AS turnId, turn.topic_id AS topicId, turn.round_no AS round,
                   turn.actor_role AS actorRole, turn.target_role AS targetRole, turn.turn_type AS turnType,
                   turn.target_claim_id AS targetClaimId, turn.target_turn_id AS targetTurnId,
                   turn.public_content AS publicContent, turn.evidence_ids_json AS evidenceIdsJson,
                   turn.stance_before AS stanceBefore, turn.stance_after AS stanceAfter,
                   turn.created_at AS createdAt
            FROM review_debate_turn turn
            JOIN review_debate_topic topic ON topic.topic_id = turn.topic_id
            WHERE topic.review_id = #{reviewId}
            ORDER BY turn.topic_id, turn.round_no, turn.turn_id
            """)
    List<TurnRow> findTurnsByReview(@Param("reviewId") String reviewId);

    @Insert("""
            INSERT INTO review_judge_decision
                (topic_id, result, public_reason_summary, accepted_claim_ids_json, rejected_claim_ids_json, decided_at)
            VALUES
                (#{row.topicId}, #{row.result}, #{row.publicReasonSummary}, #{row.acceptedClaimIdsJson},
                 #{row.rejectedClaimIdsJson}, #{row.decidedAt})
            ON DUPLICATE KEY UPDATE
                result = VALUES(result),
                public_reason_summary = VALUES(public_reason_summary),
                accepted_claim_ids_json = VALUES(accepted_claim_ids_json),
                rejected_claim_ids_json = VALUES(rejected_claim_ids_json),
                decided_at = VALUES(decided_at)
            """)
    int upsertJudgeDecision(@Param("row") JudgeDecisionRow row);

    @Select("""
            SELECT topic_id AS topicId, result, public_reason_summary AS publicReasonSummary,
                   accepted_claim_ids_json AS acceptedClaimIdsJson,
                   rejected_claim_ids_json AS rejectedClaimIdsJson, decided_at AS decidedAt
            FROM review_judge_decision
            WHERE topic_id = #{topicId}
            """)
    JudgeDecisionRow findJudgeDecision(@Param("topicId") String topicId);

    @Select("""
            SELECT decision.topic_id AS topicId, decision.result, decision.public_reason_summary AS publicReasonSummary,
                   decision.accepted_claim_ids_json AS acceptedClaimIdsJson,
                   decision.rejected_claim_ids_json AS rejectedClaimIdsJson, decision.decided_at AS decidedAt
            FROM review_judge_decision decision
            JOIN review_debate_topic topic ON topic.topic_id = decision.topic_id
            WHERE topic.review_id = #{reviewId}
            """)
    List<JudgeDecisionRow> findJudgeDecisions(@Param("reviewId") String reviewId);

    @Insert("""
            INSERT INTO review_gate_draft
                (review_id, result, decision_status, decision_actor, public_reason_summary, decided_at)
            VALUES
                (#{row.reviewId}, #{row.result}, #{row.decisionStatus}, #{row.decisionActor},
                 #{row.publicReasonSummary}, #{row.decidedAt})
            ON DUPLICATE KEY UPDATE
                result = VALUES(result),
                decision_status = VALUES(decision_status),
                decision_actor = VALUES(decision_actor),
                public_reason_summary = VALUES(public_reason_summary),
                decided_at = VALUES(decided_at)
            """)
    int upsertGateDraft(@Param("row") GateDraftRow row);

    @Select("""
            SELECT review_id AS reviewId, result, decision_status AS decisionStatus,
                   decision_actor AS decisionActor, public_reason_summary AS publicReasonSummary,
                   decided_at AS decidedAt
            FROM review_gate_draft
            WHERE review_id = #{reviewId}
            """)
    GateDraftRow findGateDraft(@Param("reviewId") String reviewId);

    /**
     * @author wangli
     */
    record ClaimRow(
            String claimId,
            String reviewId,
            String roleType,
            String subjectKey,
            String severity,
            String position,
            String status,
            String statement,
            String reasonSummary,
            String evidenceJson,
            LocalDateTime createdAt) {
    }

    /**
     * @author wangli
     */
    record TopicRow(
            String topicId,
            String reviewId,
            String subjectKey,
            String claimIdsJson,
            String status,
            int currentRound,
            String resolution,
            LocalDateTime closedAt,
            LocalDateTime createdAt) {
    }

    /**
     * @author wangli
     */
    record TurnRow(
            String turnId,
            String topicId,
            int round,
            String actorRole,
            String targetRole,
            String turnType,
            String targetClaimId,
            String targetTurnId,
            String publicContent,
            String evidenceIdsJson,
            String stanceBefore,
            String stanceAfter,
            LocalDateTime createdAt) {
    }

    /**
     * @author wangli
     */
    record JudgeDecisionRow(
            String topicId,
            String result,
            String publicReasonSummary,
            String acceptedClaimIdsJson,
            String rejectedClaimIdsJson,
            LocalDateTime decidedAt) {
    }

    /**
     * @author wangli
     */
    record GateDraftRow(
            String reviewId,
            String result,
            String decisionStatus,
            String decisionActor,
            String publicReasonSummary,
            LocalDateTime decidedAt) {
    }
}
