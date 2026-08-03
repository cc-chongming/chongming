package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.infrastructure.review.InMemoryRequirementRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [AIREVIEW-PLAN-021#2] Verifies page reads return public requirement views without exposing repository internals.
 *
 * @author zyj
 */
class RequirementQueryServiceTests {

    @Test
    void returnsAFilteredPublicPage() {
        InMemoryRequirementRepository repository = new InMemoryRequirementRepository();
        Requirement requirement = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "统一身份同步", "同步基础身份", "alice", "bob", "cx-ai", "P1");
        repository.save(requirement);
        RequirementQueryService service = new RequirementQueryService(repository);

        RequirementQueryService.RequirementPage page = service.findPage("DRAFT", "bob", "身份", 1, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().getFirst().status()).isEqualTo(RequirementStatus.DRAFT.name());
        assertThat(page.items().getFirst().title()).isEqualTo("统一身份同步");
    }
}
