package ai.cc.chongming.review.infrastructure.review;

import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.repository.RequirementRepository;
import java.util.Set;
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

    /**
     * [AIREVIEW-PLAN-027] The in-memory visibility predicate mirrors the viewer SQL:
     * creator-owned requirements plus dev-task assignments, and an empty assigned set
     * collapses to the creator condition alone.
     */
    @Test
    void scopesPageAndStatusCountsToTheViewerVisibility() {
        InMemoryRequirementRepository repository = new InMemoryRequirementRepository();
        Requirement own = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "自建需求", "张开发创建", "dev-zhang", null, "cx-ai", "P1");
        Requirement assigned = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "指派需求", "他人创建但负责任务", "pm-wang", null, "cx-ai", "P1");
        Requirement foreign = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "无关需求", "他人创建", "pm-wang", null, "cx-ai", "P2");
        repository.save(own);
        repository.save(assigned);
        repository.save(foreign);

        RequirementRepository.RequirementVisibility withAssignment =
                new RequirementRepository.RequirementVisibility("dev-zhang", Set.of(assigned.id()));
        RequirementRepository.RequirementPage scopedPage = repository.findPage(
                new RequirementRepository.RequirementFilter(null, null, null, withAssignment), 1, 20);

        assertThat(scopedPage.total()).isEqualTo(2L);
        assertThat(scopedPage.items()).extracting(Requirement::id).containsExactlyInAnyOrder(own.id(), assigned.id());
        assertThat(repository.countByStatus(withAssignment)).containsEntry(RequirementStatus.DRAFT, 2L);

        // Empty assigned set keeps only the creator condition.
        RequirementRepository.RequirementVisibility creatorOnly =
                new RequirementRepository.RequirementVisibility("dev-zhang", Set.of());
        RequirementRepository.RequirementPage creatorPage = repository.findPage(
                new RequirementRepository.RequirementFilter(null, null, null, creatorOnly), 1, 20);
        assertThat(creatorPage.total()).isEqualTo(1L);
        assertThat(creatorPage.items()).containsExactly(own);
        assertThat(repository.countByStatus(creatorOnly)).containsEntry(RequirementStatus.DRAFT, 1L);

        // Null visibility keeps the platform-wide behaviour.
        assertThat(repository.findPage(new RequirementRepository.RequirementFilter(null, null, null, null), 1, 20).total())
                .isEqualTo(3L);
        assertThat(repository.countByStatus(null)).containsEntry(RequirementStatus.DRAFT, 3L);
    }

    /**
     * [AIREVIEW-PLAN-027] Visibility composes with the historical status/assignee/keyword
     * filters instead of replacing them.
     */
    @Test
    void visibilityCombinesWithLegacyFilters() {
        InMemoryRequirementRepository repository = new InMemoryRequirementRepository();
        Requirement matching = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "身份同步", "描述", "dev-zhang", "bob", "cx-ai", "P1");
        Requirement sameCreatorOtherAssignee = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "身份导出", "描述", "dev-zhang", "carol", "cx-ai", "P1");
        repository.save(matching);
        repository.save(sameCreatorOtherAssignee);

        RequirementRepository.RequirementPage page = repository.findPage(
                new RequirementRepository.RequirementFilter(
                        RequirementStatus.DRAFT, "bob", null,
                        new RequirementRepository.RequirementVisibility("dev-zhang", Set.of())),
                1, 20);

        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.items()).containsExactly(matching);
    }
}
