package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.RequirementSnapshot;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.RequirementSnapshot.RequirementDocument;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.infrastructure.document.MarkdownRequirementParser;
import ai.cc.chongming.review.infrastructure.document.MarkdownRequirementValidator;
import ai.cc.chongming.review.infrastructure.document.RequirementSnapshotStore;
import ai.cc.chongming.review.infrastructure.document.StoredRequirementSnapshot;
import ai.cc.chongming.review.infrastructure.document.ValidatedMarkdown;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewPersistenceMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * [AIREVIEW-PLAN-011#1.2][AIREVIEW-PLAN-023#3] Creates immutable requirement snapshots and honors fenced intake cancellation.
 *
 * @author zyj
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
     * @param request intake command carrying the uniform requirement document
     * @return immutable snapshot result, or the existing result for an idempotent replay
     */
    public ReviewIntakeResult intake(ReviewIntakeRequest request) {
        request.cancellation().checkCancelled();
        validateMetadata(request);
        ValidatedMarkdown markdown = validator.validate(
                request.document().originalFilename(),
                request.document().openStream(),
                request.cancellation());

        try {
            RequirementDocument document = parser.parse(markdown.normalizedFile(), request.cancellation());
            IntakeKey key = new IntakeKey(
                    request.submitter().trim(),
                    request.idempotencyScope(),
                    request.repositoryPath() == null ? null : request.repositoryPath().trim(),
                    normalizeOptional(request.branch()),
                    normalizeOptional(request.commit()),
                    markdown.contentHash(),
                    request.remoteSource() == null ? null : request.remoteSource().identitySeed());
            synchronized (submissions) {
                request.cancellation().checkCancelled();
                ReviewIntakeResult existing = submissions.get(key);
                if (existing == null) {
                    existing = findPersistedSubmission(key);
                    if (existing != null) {
                        submissions.put(key, existing);
                    }
                }
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
                        request.repositoryPath() == null ? null : request.repositoryPath().trim(),
                        normalizeOptional(request.branch()),
                        normalizeOptional(request.commit()),
                        markdown.safeFilename(),
                        markdown.sourceHash(),
                        markdown.contentHash(),
                        MarkdownRequirementParser.PARSER_VERSION,
                        document,
                        Instant.now(),
                        request.remoteSource());
                StoredRequirementSnapshot workspaceSnapshot = snapshotStore.store(
                        snapshot, markdown, request.cancellation());
                ReviewIntakeResult created = new ReviewIntakeResult(snapshot, workspaceSnapshot, false);
                if (existing == null) {
                    request.cancellation().checkCancelled();
                    try {
                        persistReviewRoot(snapshot, key);
                    } catch (DuplicateKeyException exception) {
                        ReviewIntakeResult persisted = findPersistedSubmission(key);
                        if (persisted == null) {
                            throw exception;
                        }
                        submissions.put(key, persisted);
                        return new ReviewIntakeResult(
                                persisted.snapshot(), persisted.workspaceSnapshot(), true);
                    }
                    reviewRegistry.register(Review.pending(reviewId));
                }
                submissions.put(key, created);
                return created;
            }
        } finally {
            validator.discard(markdown);
        }
    }

    /**
     * Resolves the immutable intake snapshot that owns a currently running review attempt.
     *
     * @author zyj
     */
    public RequirementSnapshot requireSnapshot(ReviewId reviewId, int attemptNo) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        synchronized (submissions) {
            return submissions.values().stream()
                    .map(ReviewIntakeResult::snapshot)
                    .filter(snapshot -> snapshot.reviewId().equals(reviewId) && snapshot.attemptNo() == attemptNo)
                    .findFirst()
                    .orElseGet(() -> snapshotStore.load(reviewId, attemptNo));
        }
    }

    /**
     * [AIREVIEW-PLAN-010#1.7] Materializes the accepted input for a retry attempt while retaining the original input.
     *
     * @param reviewId          review owning both attempts
     * @param previousAttemptNo terminal attempt whose input is reused
     * @param retryAttemptNo    fresh pending attempt
     * @return the immutable retry input snapshot
     */
    public RequirementSnapshot copySnapshotForRetry(ReviewId reviewId, int previousAttemptNo, int retryAttemptNo) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (previousAttemptNo < 1 || retryAttemptNo <= previousAttemptNo) {
            throw new IllegalArgumentException("retry attempt must follow a positive source attempt");
        }
        synchronized (submissions) {
            if (snapshotStore.hasSnapshot(reviewId, retryAttemptNo)) {
                return snapshotStore.load(reviewId, retryAttemptNo);
            }
            RequirementSnapshot source = requireSnapshot(reviewId, previousAttemptNo);
            RequirementSnapshot target = new RequirementSnapshot(
                    UUID.randomUUID(),
                    reviewId,
                    retryAttemptNo,
                    source.submitter(),
                    source.repositoryPath(),
                    source.branch(),
                    source.commit(),
                    source.originalFilename(),
                    source.sourceHash(),
                    source.contentHash(),
                    source.parserVersion(),
                    source.document(),
                    Instant.now(),
                    source.remoteSource());
            snapshotStore.copyForNewAttempt(source, target, IntakeCancellation.neverCancelled());
            return target;
        }
    }

    private void validateMetadata(ReviewIntakeRequest request) {
        // [AIREVIEW-PLAN-029] An online repository source replaces the configured repository
        // identity; exactly one of the two must be present.
        if (request.remoteSource() == null) {
            requireText(request.repositoryPath(), "MISSING_REPOSITORY_PATH", "repositoryPath is required");
        }
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

    private ReviewIntakeResult findPersistedSubmission(IntakeKey key) {
        if (!persistenceEnabled) {
            return null;
        }
        ReviewPersistenceMapper mapper = persistenceMapperProvider == null ? null : persistenceMapperProvider.getIfAvailable();
        if (mapper == null) {
            throw new IllegalStateException("review persistence mapper is unavailable during intake");
        }
        ReviewPersistenceMapper.ReviewRow row = mapper.findReviewByInputIdempotencyKey(key.persistenceKey());
        if (row == null) {
            return null;
        }
        ReviewId reviewId = new ReviewId(UUID.fromString(row.reviewId()));
        RequirementSnapshot snapshot = snapshotStore.load(reviewId, row.attemptNo());
        reviewRegistry.register(Review.restore(
                reviewId,
                ReviewStage.valueOf(row.stage()),
                row.attemptNo(),
                row.version(),
                List.of(),
                Map.of()));
        return new ReviewIntakeResult(snapshot, snapshotStore.stored(reviewId, row.attemptNo()), true);
    }

    private void persistReviewRoot(RequirementSnapshot snapshot, IntakeKey key) {
        if (!persistenceEnabled) {
            return;
        }
        ReviewPersistenceMapper mapper = persistenceMapperProvider == null ? null : persistenceMapperProvider.getIfAvailable();
        if (mapper == null || mapper.insertReviewRequest(new ReviewPersistenceMapper.ReviewRequestRow(
                snapshot.reviewId().value().toString(),
                snapshot.snapshotId().toString(),
                snapshot.submitter(),
                "PENDING",
                key.persistenceKey(),
                snapshot.attemptNo(),
                0L)) != 1) {
            throw new IllegalStateException("review root was not persisted during intake");
        }
    }

    /**
     * Stable deduplication key scoped to the owning command, submitter, repository snapshot identity and content hash.
     *
     * @author zyj
     */
    private record IntakeKey(
            String submitter,
            String idempotencyScope,
            String repositoryPath,
            String branch,
            String commit,
            String contentHash,
            String remoteIdentity) {

        private String persistenceKey() {
            String scopeComponent = idempotencyScope == null ? "" : component("scope:" + idempotencyScope);
            String source = component(submitter) + scopeComponent + component(repositoryPath) + component(branch)
                    + component(commit) + component(contentHash) + component(remoteIdentity);
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
                return "intake:" + java.util.HexFormat.of().formatHex(digest);
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 must be available", exception);
            }
        }

        private String component(String value) {
            return value == null ? "-1:" : value.length() + ":" + value;
        }
    }
}
