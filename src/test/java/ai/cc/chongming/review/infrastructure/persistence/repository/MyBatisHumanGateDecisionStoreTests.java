package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.model.HumanGateDecision;
import ai.cc.chongming.review.domain.model.ReviewTypes.GateResult;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.infrastructure.persistence.mapper.HumanGateDecisionPersistenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [AIREVIEW-PLAN-011#1.3] Verifies the durable human Gate store round-trips every field so the
 * history view survives a restart.
 *
 * @author wangli
 */
class MyBatisHumanGateDecisionStoreTests {

    @Test
    void persistsAndReloadsConditionalDecisionWithConditions() {
        ObjectMapper objectMapper = new ObjectMapper();
        HumanGateDecisionPersistenceMapper mapper = mock(HumanGateDecisionPersistenceMapper.class);
        MyBatisHumanGateDecisionStore store = new MyBatisHumanGateDecisionStore(mapper, objectMapper);
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        HumanGateDecision decision = new HumanGateDecision(
                reviewId,
                1L,
                GateResult.CONDITIONAL,
                "Allow deployment after remediation",
                List.of("Add an authorization check", "Add a rollback plan"),
                null,
                "reviewer-1",
                null,
                Instant.parse("2026-08-07T10:00:00Z"));
        ArgumentCaptor<HumanGateDecisionPersistenceMapper.HumanGateDecisionRow> captor =
                ArgumentCaptor.forClass(HumanGateDecisionPersistenceMapper.HumanGateDecisionRow.class);
        when(mapper.insert(captor.capture())).thenReturn(1);
        when(mapper.findVersions(reviewId.value().toString())).thenAnswer(invocation -> List.of(captor.getValue()));

        store.append(decision);
        List<HumanGateDecision> versions = store.findVersions(reviewId);

        assertThat(versions).hasSize(1);
        HumanGateDecision reloaded = versions.get(0);
        assertThat(reloaded.reviewId()).isEqualTo(reviewId);
        assertThat(reloaded.gateVersion()).isEqualTo(1L);
        assertThat(reloaded.result()).isEqualTo(GateResult.CONDITIONAL);
        assertThat(reloaded.reason()).isEqualTo("Allow deployment after remediation");
        assertThat(reloaded.conditions()).containsExactly("Add an authorization check", "Add a rollback plan");
        assertThat(reloaded.overrideReason()).isNull();
        assertThat(reloaded.reviewerId()).isEqualTo("reviewer-1");
        assertThat(reloaded.supersedesVersion()).isNull();
        assertThat(reloaded.decidedAt()).isEqualTo(Instant.parse("2026-08-07T10:00:00Z"));
    }

    @Test
    void findLatestReturnsNewestVersionAndOverridesRoundTrip() {
        ObjectMapper objectMapper = new ObjectMapper();
        HumanGateDecisionPersistenceMapper mapper = mock(HumanGateDecisionPersistenceMapper.class);
        MyBatisHumanGateDecisionStore store = new MyBatisHumanGateDecisionStore(mapper, objectMapper);
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        HumanGateDecision override = new HumanGateDecision(
                reviewId,
                2L,
                GateResult.OVERRIDE,
                "Escalate to owner approval",
                List.of(),
                "Business critical launch cannot wait",
                "reviewer-2",
                1L,
                Instant.parse("2026-08-07T11:30:00Z"));
        ArgumentCaptor<HumanGateDecisionPersistenceMapper.HumanGateDecisionRow> captor =
                ArgumentCaptor.forClass(HumanGateDecisionPersistenceMapper.HumanGateDecisionRow.class);
        when(mapper.insert(captor.capture())).thenReturn(1);
        when(mapper.findLatest(reviewId.value().toString())).thenAnswer(invocation -> captor.getValue());

        store.append(override);
        HumanGateDecision reloaded = store.findLatest(reviewId).orElseThrow();

        assertThat(reloaded.result()).isEqualTo(GateResult.OVERRIDE);
        assertThat(reloaded.overrideReason()).isEqualTo("Business critical launch cannot wait");
        assertThat(reloaded.supersedesVersion()).isEqualTo(1L);
        assertThat(reloaded.conditions()).isEmpty();
    }
}
