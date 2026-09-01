package ai.cc.chongming.review.application;

import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.exception.RequirementErrorCode;
import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RemoteRepositorySource;
import ai.cc.chongming.review.domain.model.RequirementSnapshot;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.RequirementRepository.RequirementVisibility;
import ai.cc.chongming.review.infrastructure.document.RequirementSnapshotStore;
import ai.cc.chongming.review.infrastructure.document.ValidatedMarkdown;
import ai.cc.chongming.review.infrastructure.review.InMemoryRequirementRepository;
import ai.cc.chongming.review.infrastructure.review.InMemoryReviewRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [AIREVIEW-PLAN-021#2] Verifies page reads return public requirement views without exposing repository internals.
 *
 * @author zyj
 */
class RequirementQueryServiceTests {

    @TempDir
    Path workspaceRoot;

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

    /**
     * [AIREVIEW-PLAN-029] The public view exposes the online repository url and ref while the
     * encrypted token is reduced to a boolean so cipher text can never reach a client.
     */
    @Test
    void projectsTheRemoteSourceWithoutItsCipherText() {
        InMemoryRequirementRepository repository = new InMemoryRequirementRepository();
        Requirement requirement = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "线上仓库需求", "# 目标", "alice", null, null,
                new RemoteRepositorySource("https://example.com/group/demo.git", "main", "v1:cipher"), "P1");
        repository.save(requirement);
        RequirementQueryService service = new RequirementQueryService(repository);

        RequirementQueryService.RequirementView view = service.findById(requirement.id());

        assertThat(view.repositoryPath()).isNull();
        assertThat(view.remote()).isNotNull();
        assertThat(view.remote().url()).isEqualTo("https://example.com/group/demo.git");
        assertThat(view.remote().ref()).isEqualTo("main");
        assertThat(view.remote().tokenConfigured()).isTrue();
    }

    /**
     * [AIREVIEW-PLAN-111] The uploaded raw Markdown snapshot is served verbatim with its file name.
     */
    @Test
    void findDocumentReturnsTheStoredRawMarkdown() throws Exception {
        InMemoryRequirementRepository repository = new InMemoryRequirementRepository();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Requirement requirement = Requirement.restore(
                new RequirementId(UUID.randomUUID()), "上传需求", "", "alice", null, "cx-ai", "P1",
                RequirementStatus.PENDING_REVIEW, reviewId, Instant.now(), Instant.now(), 0L);
        repository.save(requirement);
        String rawMarkdown = "# 需求\n\n上传原文";
        RequirementQueryService service = serviceWithStoredDocument(repository, reviewId, rawMarkdown);

        RequirementQueryService.RequirementDocumentView document = service.findDocument(requirement.id(), null);

        assertThat(document.reviewId()).isEqualTo(reviewId.value());
        assertThat(document.attemptNo()).isEqualTo(1);
        assertThat(document.filename()).isEqualTo("requirement.md");
        assertThat(document.markdown()).isEqualTo(rawMarkdown);
    }

    /**
     * [AIREVIEW-PLAN-111] Draft requirements without a bound review surface the same not-found
     * contract as missing requirements.
     */
    @Test
    void findDocumentWithoutReviewBindingSurfacesNotFound() {
        InMemoryRequirementRepository repository = new InMemoryRequirementRepository();
        Requirement requirement = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "未上传需求", "描述", "alice", null, "cx-ai", "P1");
        repository.save(requirement);
        RequirementQueryService service = new RequirementQueryService(
                repository,
                new RequirementSnapshotStore(new ReviewProperties(workspaceRoot.toString(), 8, 2)));

        assertThatThrownBy(() -> service.findDocument(requirement.id(), null))
                .isInstanceOfSatisfying(RequirementDomainException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(RequirementErrorCode.REQUIREMENT_NOT_FOUND));
    }

    /**
     * [AIREVIEW-PLAN-111] The document read honours the same viewer scope as the detail read:
     * hidden requirements surface 404 without leaking existence, while creator and task owners
     * still receive the uploaded Markdown.
     */
    @Test
    void findDocumentHonoursTheViewerVisibility() throws Exception {
        InMemoryRequirementRepository repository = new InMemoryRequirementRepository();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Requirement requirement = Requirement.restore(
                new RequirementId(UUID.randomUUID()), "上传需求", "", "alice", null, "cx-ai", "P1",
                RequirementStatus.PENDING_REVIEW, reviewId, Instant.now(), Instant.now(), 0L);
        repository.save(requirement);
        RequirementQueryService service = serviceWithStoredDocument(
                repository, reviewId, "# 需求\n\n可见正文");

        RequirementVisibility hiddenScope = new RequirementVisibility("mallory", Set.of());
        assertThatThrownBy(() -> service.findDocument(requirement.id(), hiddenScope))
                .isInstanceOfSatisfying(RequirementDomainException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(RequirementErrorCode.REQUIREMENT_NOT_FOUND));
        assertThat(service.findDocument(requirement.id(), new RequirementVisibility("alice", Set.of())).markdown())
                .isEqualTo("# 需求\n\n可见正文");
        assertThat(service.findDocument(
                requirement.id(), new RequirementVisibility("dev-task-owner", Set.of(requirement.id()))).filename())
                .isEqualTo("requirement.md");
    }

    /**
     * [AIREVIEW-PLAN-112#1] 重启后内存注册表不认识已完成评审：文档读取必须只依赖磁盘快照，
     * 且多个 attempt 并存时取最新快照。
     */
    @Test
    void findDocumentServesTheLatestStoredAttemptWithoutRegistryKnowledge() throws Exception {
        InMemoryRequirementRepository repository = new InMemoryRequirementRepository();
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        Requirement requirement = Requirement.restore(
                new RequirementId(UUID.randomUUID()), "上传需求", "", "alice", null, "cx-ai", "P1",
                RequirementStatus.PENDING_REVIEW, reviewId, Instant.now(), Instant.now(), 0L);
        repository.save(requirement);
        RequirementSnapshotStore store = new RequirementSnapshotStore(
                new ReviewProperties(workspaceRoot.toString(), 8, 2));
        storeSnapshot(store, reviewId, 1, "# 旧版本");
        storeSnapshot(store, reviewId, 2, "# 新版本");
        RequirementQueryService service = new RequirementQueryService(repository, store);

        RequirementQueryService.RequirementDocumentView document = service.findDocument(requirement.id(), null);

        assertThat(document.attemptNo()).isEqualTo(2);
        assertThat(document.markdown()).isEqualTo("# 新版本");
    }

    private RequirementQueryService serviceWithStoredDocument(
            InMemoryRequirementRepository repository,
            ReviewId reviewId,
            String rawMarkdown) throws Exception {
        RequirementSnapshotStore store = new RequirementSnapshotStore(
                new ReviewProperties(workspaceRoot.toString(), 8, 2));
        storeSnapshot(store, reviewId, 1, rawMarkdown);
        return new RequirementQueryService(repository, store);
    }

    private void storeSnapshot(
            RequirementSnapshotStore store, ReviewId reviewId, int attemptNo, String rawMarkdown) throws Exception {
        Path rawFile = Files.createTempFile(workspaceRoot, "requirement-raw-", ".md");
        Path normalizedFile = Files.createTempFile(workspaceRoot, "requirement-normalized-", ".md");
        Files.writeString(rawFile, rawMarkdown);
        Files.writeString(normalizedFile, rawMarkdown);
        ValidatedMarkdown markdown = new ValidatedMarkdown(
                "requirement.md", rawFile, normalizedFile, "a".repeat(64), "b".repeat(64),
                rawMarkdown.getBytes(StandardCharsets.UTF_8).length);
        RequirementSnapshot snapshot = new RequirementSnapshot(
                UUID.randomUUID(), reviewId, attemptNo, "reviewer", "cx-ai", null, null,
                "requirement.md", "a".repeat(64), "b".repeat(64), "test",
                new RequirementSnapshot.RequirementDocument(List.of(), List.of(), 0, 0, false),
                Instant.now());
        store.store(snapshot, markdown, IntakeCancellation.neverCancelled());
    }
}
