package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewAssessment;
import ai.cc.chongming.review.infrastructure.assessment.InMemoryReviewAssessmentStore;
import ai.cc.chongming.review.infrastructure.debate.InMemoryReviewDebateStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * [AIREVIEW-PLAN-024#方案4] Verifies the deterministic conflict detection front-end: single
 * GAP/UNKNOWN risks never form debate topics, only contradictory conclusions do, every candidate
 * keeps a one-to-one audit record with its later registration or explicit skip, and every debate
 * counter is derived from single batch store reads.
 *
 * @author wangli
 */
class ConflictDetectionServiceTests {

    @Test
    void loneGapIsOnlyAGateRiskAndNeverFormsATopic() {
        InMemoryReviewAssessmentStore assessmentStore = new InMemoryReviewAssessmentStore();
        InMemoryReviewDebateStore debateStore = new InMemoryReviewDebateStore();
        ConflictDetectionService service = new ConflictDetectionService(assessmentStore, debateStore);
        Review review = Review.pending(new ReviewId(UUID.randomUUID()));
        debateStore.saveClaim(claim(review.id(), RoleType.PRODUCT, ClaimPosition.SUPPORT, "auth.token_policy"));
        assessmentStore.saveBatch(review.id(), review.attemptNo(), List.of(
                assessment(review.id(), RoleType.BACKEND, AssessmentStatus.GAP, "auth.refresh_flow")));

        ConflictDetectionService.Outcome outcome = service.detect(review);

        // 「单一风险」有 GAP 无相反结论不建辩题；GAP 仅作为 Gate 风险输入。
        assertThat(outcome.result().candidates()).isEmpty();
        assertThat(outcome.gateRiskAssessments()).singleElement()
                .satisfies(risk -> assertThat(risk.checkpointKey()).isEqualTo("auth.refresh_flow"));
        // 每个被检查的主题都有一条审计记录：GAP 主题与无冲突的 SUPPORT 主题均为 NO_CONFLICT。
        assertThat(outcome.auditRecords())
                .allSatisfy(record -> assertThat(record.disposition())
                        .isEqualTo(ConflictDetectionService.ConflictAuditDisposition.NO_CONFLICT));
        assertThat(outcome.auditRecords())
                .filteredOn(record -> record.subjectKey().equals("auth.refresh_flow"))
                .singleElement()
                .extracting(ConflictDetectionService.ConflictAuditRecord::claimIds)
                .isEqualTo(List.of());
    }

    @Test
    void contradictoryConclusionsProduceCandidatesWithOneToOneAuditDispositions() {
        InMemoryReviewAssessmentStore assessmentStore = new InMemoryReviewAssessmentStore();
        InMemoryReviewDebateStore debateStore = new InMemoryReviewDebateStore();
        ConflictDetectionService service = new ConflictDetectionService(assessmentStore, debateStore);
        Review review = Review.pending(new ReviewId(UUID.randomUUID()));
        Claim support = claim(review.id(), RoleType.PRODUCT, ClaimPosition.SUPPORT, "auth.token_policy");
        Claim oppose = claim(review.id(), RoleType.BACKEND, ClaimPosition.OPPOSE, "auth.token_policy");
        debateStore.saveClaim(support);
        debateStore.saveClaim(oppose);
        assessmentStore.saveBatch(review.id(), review.attemptNo(), List.of(
                assessment(review.id(), RoleType.FRONTEND, AssessmentStatus.CONFIRMED, "auth.session_ui"),
                assessment(review.id(), RoleType.BACKEND, AssessmentStatus.GAP, "auth.session_ui"),
                assessment(review.id(), RoleType.BACKEND, AssessmentStatus.UNKNOWN, "auth.retry_policy")));

        ConflictDetectionService.Outcome outcome = service.detect(review);

        assertThat(outcome.result().candidates())
                .extracting(candidate -> candidate.subjectKey())
                .containsExactlyInAnyOrder("auth.token_policy", "auth.session_ui");
        assertThat(outcome.gateRiskAssessments()).hasSize(2);
        assertThat(outcome.auditRecords())
                .filteredOn(record -> record.disposition() == ConflictDetectionService.ConflictAuditDisposition.DETECTED)
                .extracting(ConflictDetectionService.ConflictAuditRecord::subjectKey)
                .containsExactlyInAnyOrder("auth.token_policy", "auth.session_ui");

        // 登记一个主题、明确跳过另一个后，每个候选都有且仅有一条对应审计记录。
        service.recordTopicRegistration(review.id(), List.of("auth.token_policy"));
        List<ConflictDetectionService.ConflictAuditRecord> finalized = service.auditRecords(review.id());
        assertThat(finalized)
                .filteredOn(record -> record.subjectKey().equals("auth.token_policy"))
                .singleElement()
                .extracting(ConflictDetectionService.ConflictAuditRecord::disposition)
                .isEqualTo(ConflictDetectionService.ConflictAuditDisposition.REGISTERED);
        assertThat(finalized)
                .filteredOn(record -> record.subjectKey().equals("auth.session_ui"))
                .singleElement()
                .extracting(ConflictDetectionService.ConflictAuditRecord::disposition)
                .isEqualTo(ConflictDetectionService.ConflictAuditDisposition.SKIPPED);
    }

    @Test
    void derivesDebateMetricsFromSingleBatchStoreReads() {
        InMemoryReviewAssessmentStore assessmentStore = new InMemoryReviewAssessmentStore();
        InMemoryReviewDebateStore debateStore = new InMemoryReviewDebateStore();
        ConflictDetectionService service = new ConflictDetectionService(assessmentStore, debateStore);
        Review review = Review.pending(new ReviewId(UUID.randomUUID()));
        Claim support = claim(review.id(), RoleType.PRODUCT, ClaimPosition.SUPPORT, "auth.token_policy");
        Claim oppose = claim(review.id(), RoleType.BACKEND, ClaimPosition.OPPOSE, "auth.token_policy");
        debateStore.saveClaim(support);
        debateStore.saveClaim(oppose);
        assessmentStore.saveBatch(review.id(), review.attemptNo(), List.of(
                assessment(review.id(), RoleType.BACKEND, AssessmentStatus.UNKNOWN, "auth.retry_policy")));
        debateStore.saveTopic(new ai.cc.chongming.review.domain.model.DebateTopic(
                new TopicId(UUID.randomUUID()), review.id(), "auth.token_policy",
                List.of(support.claimId(), oppose.claimId())));

        ConflictDetectionService.DebateMetrics metrics = service.debateMetrics(review);

        assertThat(metrics.conflictCandidateCount()).isEqualTo(1);
        assertThat(metrics.registeredTopicCount()).isEqualTo(1);
        // 剩余风险：一个 UNKNOWN 评估；两个相反 Claim 均已被主题覆盖。
        assertThat(metrics.remainingRiskCount()).isEqualTo(1);
        // 未闭环动作：一个非终态 OPEN 主题。
        assertThat(metrics.unclosedActionCount()).isEqualTo(1);
    }

    private Claim claim(ReviewId reviewId, RoleType roleType, ClaimPosition position, String subjectKey) {
        return new Claim(new ClaimId(UUID.randomUUID()), reviewId, roleType, subjectKey, ClaimSeverity.P1, position,
                "Statement", "Reason", List.of());
    }

    private ReviewAssessment assessment(ReviewId reviewId, RoleType roleType, AssessmentStatus status, String checkpointKey) {
        return new ReviewAssessment(reviewId, 1, roleType, checkpointKey, status, "结论摘要",
                status.requiresReasonSummary() ? "风险原因说明" : "", List.of(),
                ReviewAssessment.idempotencyKeyFor(reviewId, 1, roleType, checkpointKey), Instant.now());
    }
}
