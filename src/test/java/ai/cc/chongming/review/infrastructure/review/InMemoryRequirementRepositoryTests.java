package ai.cc.chongming.review.infrastructure.review;

import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.repository.RequirementRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [AIREVIEW-PLAN-021#2] Exercises in-memory filtering semantics shared with the persistent repository.
 *
 * @author zyj
 */
class InMemoryRequirementRepositoryTests {

    @Test
    void filtersByStatusAssigneeAndKeywordWithOneBasedPaging() {
        InMemoryRequirementRepository repository = new InMemoryRequirementRepository();
        Requirement first = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "身份同步", "校园基础身份同步", "alice", "bob", "cx-ai", "P1");
        Requirement second = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "消息订阅", "课堂通知", "alice", "carol", "cx-ai", "P2");
        repository.save(first);
        repository.save(second);

        RequirementRepository.RequirementPage page = repository.findPage(
                new RequirementRepository.RequirementFilter(RequirementStatus.DRAFT, "bob", "身份"), 1, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).containsExactly(first);
        assertThat(repository.countByStatus()).containsEntry(RequirementStatus.DRAFT, 2L);
    }

    @Test
    void returnsAnEmptyPageForTheLargestValidPageNumberWithoutOverflowing() {
        InMemoryRequirementRepository repository = new InMemoryRequirementRepository();
        repository.save(Requirement.draft(
                new RequirementId(UUID.randomUUID()), "身份同步", "校园基础身份同步", "alice", "bob", "cx-ai", "P1"));

        RequirementRepository.RequirementPage page = repository.findPage(
                new RequirementRepository.RequirementFilter(null, null, null), Integer.MAX_VALUE, 100);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isEqualTo(1L);
    }
}
