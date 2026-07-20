package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.RequirementSnapshot;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.RequirementSnapshot.RequirementDocument;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.infrastructure.document.MarkdownRequirementParser;
import ai.cc.chongming.review.infrastructure.document.MarkdownRequirementValidator;
import ai.cc.chongming.review.infrastructure.document.RequirementSnapshotStore;
import ai.cc.chongming.review.infrastructure.document.StoredRequirementSnapshot;
import ai.cc.chongming.review.infrastructure.document.ValidatedMarkdown;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewPersistenceMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * [AIREVIEW-PLAN-011#1.2] Creates immutable requirement snapshots and applies deterministic intake idempotency.
 *
 * @author wangli
 */
@Service
public class ReviewIntakeService {

    private final MarkdownRequirementValidator validator;
    private final MarkdownRequirementParser parser;
    private final RequirementSnapshotStore snapshotStore;
    private final ReviewRegistry reviewRegistry;
    private final ObjectProvider<ReviewPersistenceMapper> persistenceMapperProvider;
    private final boolean persistenceEnabled;
    private final Map<IntakeKey, ReviewIntakeResult> submissions = new HashMap<>();

    public ReviewIntakeService(
            MarkdownRequirementValidator validator,
            MarkdownRequirementParser parser,
            RequirementSnapshotStore snapshotStore) {
        this(validator, parser, snapshotStore, ReviewRegistry.noop());
    }

    public ReviewIntakeService(
            MarkdownRequirementValidator validator,
            MarkdownRequirementParser parser,
            RequirementSnapshotStore snapshotStore,
            ReviewRegistry reviewRegistry) {
        this(validator, parser, snapshotStore, reviewRegistry, null, false);
    }

    @Autowired
    public ReviewIntakeService(
            MarkdownRequirementValidator validator,
            MarkdownRequirementParser parser,
            RequirementSnapshotStore snapshotStore,
            ReviewRegistry reviewRegistry,
            ObjectProvider<ReviewPersistenceMapper> persistenceMapperProvider,
            @Value("${review.persistence.enabled:false}") boolean persistenceEnabled) {
        this.validator = validator;
        this.parser = parser;
        this.snapshotStore = snapshotStore;
        this.reviewRegistry = reviewRegistry;
        this.persistenceMapperProvider = persistenceMapperProvider;
        this.persistenceEnabled = persistenceEnabled;
    }

    /**
     * Validates, normalizes and stores a Markdown requirement before later review orchestration.
     *
     * @param request multipart intake command
     * @return immutable snapshot result, or the existing result for an idempotent replay
     */
    public ReviewIntakeResult intake(ReviewIntakeRequest request) {
        request.cancellation().checkCancelled();
        validateMetadata(request);
        ValidatedMarkdown markdown;
        try {
            markdown = validator.validate(
                    request.requirementFile().getOriginalFilename(),
                    request.requirementFile().getInputStream(),
                    request.cancellation());
        } catch (IOException exception) {
            throw ReviewIntakeException.badRequest("UNREADABLE_UPLOAD", "Unable to read uploaded Markdown file");
        }

        try {
            RequirementDocument document = parser.parse(markdown.normalizedFile(), request.cancellation());
            IntakeKey key = new IntakeKey(
                    request.submitter().trim(),
                    request.repositoryPath().trim(),
                    normalizeOptional(request.branch()),
                    normalizeOptional(request.commit()),
                    markdown.contentHash());
            synchronized (submissions) {
                request.cancellation().checkCancelled();
                ReviewIntakeResult existing = submissions.get(key);
                if (existing != null && !request.forceNewAttempt()) {
                    return new ReviewIntakeResult(existing.snapshot(), existing.workspaceSnapshot(), true);
                }

                ReviewId reviewId = existing == null
                        ? new ReviewId(UUID.randomUUID())
                        : existing.snapshot().reviewId();
                int attemptNo = existing == null ? 1 : existing.snapshot().attemptNo() + 1;
                RequirementSnapshot snapshot = new RequirementSnapshot(
                        UUID.randomUUID(),
                        reviewId,
                        attemptNo,
                        request.submitter().trim(),
                        request.repositoryPath().trim(),
                        normalizeOptional(request.branch()),
                        normalizeOptional(request.commit()),
                        markdown.safeFilename(),
                        markdown.sourceHash(),
                        markdown.contentHash(),
                        MarkdownRequirementParser.PARSER_VERSION,
                        document,
                        Instant.now());
                StoredRequirementSnapshot workspaceSnapshot = snapshotStore.store(
                        snapshot, markdown, request.cancellation());
                ReviewIntakeResult created = new ReviewIntakeResult(snapshot, workspaceSnapshot, false);
                if (existing == null) {
                    persistReviewRoot(snapshot);
                    reviewRegistry.register(Review.pending(reviewId));
                }
                submissions.put(key, created);
                return created;
            }
        } finally {
            validator.discard(markdown);
        }
    }

    private void validateMetadata(ReviewIntakeRequest request) {
        requireText(request.repositoryPath(), "MISSING_REPOSITORY_PATH", "repositoryPath is required");
        requireText(request.submitter(), "MISSING_SUBMITTER", "submitter is required");
    }

    private void requireText(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw ReviewIntakeException.badRequest(code, message);
        }
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void persistReviewRoot(RequirementSnapshot snapshot) {
        if (!persistenceEnabled) {
            return;
        }
        ReviewPersistenceMapper mapper = persistenceMapperProvider == null ? null : persistenceMapperProvider.getIfAvailable();
        if (mapper == null || mapper.insertReviewRequest(new ReviewPersistenceMapper.ReviewRequestRow(
                snapshot.reviewId().value().toString(),
                snapshot.snapshotId().toString(),
                snapshot.submitter(),
                "PENDING",
                "intake:" + snapshot.snapshotId(),
                snapshot.attemptNo(),
                0L)) != 1) {
            throw new IllegalStateException("review root was not persisted during intake");
        }
    }

    /**
     * Stable deduplication key scoped to submitter, repository snapshot identity and content hash.
     *
     * @author wangli
     */
    private record IntakeKey(
            String submitter, String repositoryPath, String branch, String commit, String contentHash) {
    }
}
