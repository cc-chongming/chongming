package ai.cc.chongming.review.domain;

import ai.cc.chongming.review.domain.model.DebateTopic;
import ai.cc.chongming.review.domain.model.ReviewTypes.ClaimId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.TopicId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [AIREVIEW-PLAN-040#1] Verifies the idempotent support-claim mutator added to DebateTopic.
 *
 * @author wangli
 */
class DebateTopicTests {

    @Test
    void appendsANewClaimIdAfterTheOriginalMembers() {
        ClaimId first = new ClaimId(UUID.randomUUID());
        ClaimId second = new ClaimId(UUID.randomUUID());
        ClaimId support = new ClaimId(UUID.randomUUID());
        DebateTopic topic = new DebateTopic(new TopicId(UUID.randomUUID()), new ReviewId(UUID.randomUUID()),
                "authentication", List.of(first, second));

        topic.attachClaim(support);

        assertThat(topic.claimIds()).containsExactly(first, second, support);
    }

    @Test
    void attachClaimIsIdempotentForAnAlreadyMountedClaimId() {
        ClaimId member = new ClaimId(UUID.randomUUID());
        DebateTopic topic = new DebateTopic(new TopicId(UUID.randomUUID()), new ReviewId(UUID.randomUUID()),
                "authentication", List.of(member));

        topic.attachClaim(member);
        topic.attachClaim(member);

        assertThat(topic.claimIds()).containsExactly(member);
    }

    @Test
    void carriesTheOptionalChinesePublicTitle() {
        DebateTopic titled = new DebateTopic(new TopicId(UUID.randomUUID()), new ReviewId(UUID.randomUUID()),
                "authentication", List.of(), "认证失败需人工介入");
        DebateTopic legacy = new DebateTopic(new TopicId(UUID.randomUUID()), new ReviewId(UUID.randomUUID()),
                "authentication", List.of());

        assertThat(titled.publicTitle()).isEqualTo("认证失败需人工介入");
        assertThat(legacy.publicTitle()).isNull();
    }
}
