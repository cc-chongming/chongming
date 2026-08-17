package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.exception.RequirementErrorCode;
import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.repository.RequirementRepository.RequirementVisibility;
import ai.cc.chongming.review.infrastructure.review.InMemoryRequirementRepository;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * [AIREVIEW-PLAN-027] Viewer-scoped detail reads surface the same not-found error for
     * hidden and missing requirements so existence is never leaked.
     */
    @Test
    void findByIdHonoursTheViewerVisibility() {
        InMemoryRequirementRepository repository = new InMemoryRequirementRepository();
        Requirement requirement = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "统一身份同步", "同步基础身份", "alice", null, "cx-ai", "P1");
        repository.save(requirement);
        RequirementQueryService service = new RequirementQueryService(repository);

        // Creator and assigned viewers see the detail; null visibility keeps the open read.
        assertThat(service.findById(requirement.id(), new RequirementVisibility("alice", Set.of())).id())
                .isEqualTo(requirement.id().value());
        assertThat(service.findById(requirement.id(), new RequirementVisibility("dev-task-owner", Set.of(requirement.id()))).id())
                .isEqualTo(requirement.id().value());
        assertThat(service.findById(requirement.id(), null).id()).isEqualTo(requirement.id().value());
        assertThat(service.findById(requirement.id()).id()).isEqualTo(requirement.id().value());

        // Hidden requirements surface the identical not-found contract as missing ones.
        RequirementVisibility hiddenScope = new RequirementVisibility("mallory", Set.of());
        assertThatThrownBy(() -> service.findById(requirement.id(), hiddenScope))
                .isInstanceOfSatisfying(RequirementDomainException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(RequirementErrorCode.REQUIREMENT_NOT_FOUND));
        assertThatThrownBy(() -> service.findById(new RequirementId(UUID.randomUUID()), hiddenScope))
                .isInstanceOfSatisfying(RequirementDomainException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(RequirementErrorCode.REQUIREMENT_NOT_FOUND));
    }
}
