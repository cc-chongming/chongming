package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.exception.RequirementErrorCode;
import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.RequirementRepository;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.infrastructure.document.RequirementSnapshotStore;
import ai.cc.chongming.review.infrastructure.document.StoredRequirementSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [AIREVIEW-PLAN-021#2] Builds public requirement read models from the lifecycle aggregate.
 *
 * @author zyj
 */
@Service
public class RequirementQueryService {

    private final RequirementRepository requirementRepository;
    private final RequirementSnapshotStore snapshotStore;
    private final ReviewRegistry reviewRegistry;

    public RequirementQueryService(RequirementRepository requirementRepository) {
        this(requirementRepository, null, ReviewRegistry.noop());
    }

    @Autowired
    public RequirementQueryService(
            RequirementRepository requirementRepository,
            RequirementSnapshotStore snapshotStore,
            ReviewRegistry reviewRegistry) {
        this.requirementRepository = Objects.requireNonNull(requirementRepository, "requirementRepository must not be null");
        this.snapshotStore = snapshotStore;
        this.reviewRegistry = Objects.requireNonNull(reviewRegistry, "reviewRegistry must not be null");
    }

    @Transactional(readOnly = true)
    public RequirementPage findPage(String status, String assignee, String keyword, int page, int size) {
        return findPage(status, assignee, keyword, page, size, null);
    }

    /**
     * [AIREVIEW-PLAN-027] Viewer-scoped page read; a {@code null} visibility keeps the
     * historical platform-wide listing.
     */
    @Transactional(readOnly = true)
    public RequirementPage findPage(
            String status, String assignee, String keyword, int page, int size,
            RequirementRepository.RequirementVisibility visibility) {
        RequirementRepository.RequirementPage result = requirementRepository.findPage(
                new RequirementRepository.RequirementFilter(
                        parseStatus(status), normalize(assignee), normalize(keyword), visibility),
                page, size);
        return new RequirementPage(result.items().stream().map(RequirementView::from).toList(), result.page(), result.size(), result.total());
    }

    @Transactional(readOnly = true)
    public RequirementView findById(RequirementId requirementId) {
        return findById(requirementId, null);
    }

    /**
     * [AIREVIEW-PLAN-027] Viewer-scoped detail read; requirements outside the viewer's scope
     * surface the same {@code REQUIREMENT_NOT_FOUND} error as missing ones so existence is never
     * leaked.
     */
    @Transactional(readOnly = true)
    public RequirementView findById(RequirementId requirementId, RequirementRepository.RequirementVisibility visibility) {
        return RequirementView.from(requireVisible(requirementId, visibility));
    }

    /**
     * [AIREVIEW-PLAN-111] Shared existence and visibility gate for detail and document reads;
     * hidden requirements surface the same not-found error as missing ones.
     */
    private Requirement requireVisible(RequirementId requirementId, RequirementRepository.RequirementVisibility visibility) {
        Requirement requirement = requirementRepository
                .findById(Objects.requireNonNull(requirementId, "requirementId must not be null"))
                .orElseThrow(() -> new RequirementDomainException(
                        RequirementErrorCode.REQUIREMENT_NOT_FOUND, "requirement was not found"));
        if (visibility != null && !visibleTo(requirement, visibility)) {
            throw new RequirementDomainException(
                    RequirementErrorCode.REQUIREMENT_NOT_FOUND, "requirement was not found");
        }
        return requirement;
    }

    /**
     * [AIREVIEW-PLAN-111] Returns the raw Markdown uploaded for the requirement's active review
     * attempt. Requirements without a bound review, an unregistered review or a missing snapshot
     * surface the same {@code REQUIREMENT_NOT_FOUND} contract as missing requirements.
     */
    @Transactional(readOnly = true)
    public RequirementDocumentView findDocument(
            RequirementId requirementId, RequirementRepository.RequirementVisibility visibility) {
        Requirement requirement = requireVisible(requirementId, visibility);
        ReviewId reviewId = requirement.reviewId();
        if (reviewId == null) {
            throw new RequirementDomainException(
                    RequirementErrorCode.REQUIREMENT_NOT_FOUND, "requirement document was not found");
        }
        if (snapshotStore == null) {
            throw new IllegalStateException("RequirementSnapshotStore is not configured");
        }
        Review review = reviewRegistry.find(reviewId)
                .orElseThrow(() -> new RequirementDomainException(
                        RequirementErrorCode.REQUIREMENT_NOT_FOUND, "requirement document was not found"));
        int attemptNo = review.attemptNo();
        if (!snapshotStore.hasSnapshot(reviewId, attemptNo)) {
            throw new RequirementDomainException(
                    RequirementErrorCode.REQUIREMENT_NOT_FOUND, "requirement document was not found");
        }
        StoredRequirementSnapshot stored = snapshotStore.stored(reviewId, attemptNo);
        Path rawMarkdownPath = stored.rawMarkdownPath();
        Path markdownPath = Files.isRegularFile(rawMarkdownPath) ? rawMarkdownPath : stored.normalizedMarkdownPath();
        String markdown;
        try {
            markdown = Files.readString(markdownPath);
        } catch (IOException exception) {
            throw new RequirementDomainException(
                    RequirementErrorCode.REQUIREMENT_NOT_FOUND, "requirement document was not found");
        }
        return new RequirementDocumentView(reviewId.value(), attemptNo, rawMarkdownPath.getFileName().toString(), markdown);
    }

    private boolean visibleTo(Requirement requirement, RequirementRepository.RequirementVisibility visibility) {
        return visibility.viewerUsername().equals(requirement.creatorId())
                || visibility.assignedRequirementIds().contains(requirement.id());
    }

    private RequirementStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return RequirementStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported requirement status: " + value, exception);
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * @author zyj
     */
    public record RequirementPage(java.util.List<RequirementView> items, int page, int size, long total) {
        public RequirementPage {
            items = java.util.List.copyOf(items);
        }
    }

    /**
     * @author zyj
     */
    public record RequirementView(
            java.util.UUID id,
            String title,
            String description,
            String status,
            String creatorId,
            String assigneeId,
            String repositoryPath,
            String priority,
            java.util.UUID reviewId,
            long version,
            String createdAt,
            String updatedAt,
            RemoteView remote) {

        /** [AIREVIEW-PLAN-029] Backward-compatible projection without the remote source. */
        public RequirementView(
                java.util.UUID id,
                String title,
                String description,
                String status,
                String creatorId,
                String assigneeId,
                String repositoryPath,
                String priority,
                java.util.UUID reviewId,
                long version,
                String createdAt,
                String updatedAt) {
            this(id, title, description, status, creatorId, assigneeId, repositoryPath, priority,
                    reviewId, version, createdAt, updatedAt, null);
        }

        public static RequirementView from(Requirement requirement) {
            ai.cc.chongming.review.domain.model.RemoteRepositorySource remoteSource = requirement.remoteSource();
            RemoteView remote = remoteSource == null
                    ? null
                    : new RemoteView(remoteSource.url(), remoteSource.ref(), remoteSource.encryptedToken() != null);
            return new RequirementView(
                    requirement.id().value(),
                    requirement.title(),
                    requirement.description(),
                    requirement.status().name(),
                    requirement.creatorId(),
                    requirement.assigneeId(),
                    requirement.repositoryPath(),
                    requirement.priority(),
                    requirement.reviewId() == null ? null : requirement.reviewId().value(),
                    requirement.version(),
                    requirement.createdAt().toString(),
                    requirement.updatedAt().toString(),
                    remote);
        }
    }

    /**
     * [AIREVIEW-PLAN-111] Uploaded Markdown requirement document for the active review attempt.
     *
     * @author wangli
     */
    public record RequirementDocumentView(
            java.util.UUID reviewId,
            int attemptNo,
            String filename,
            String markdown) {
    }

    /**
     * [AIREVIEW-PLAN-029] Public online repository projection; the token is only ever reported as
     * a boolean so cipher text can never reach a client.
     *
     * @author wangli
     */
    public record RemoteView(String url, String ref, boolean tokenConfigured) {
    }
}
