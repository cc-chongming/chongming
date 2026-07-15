package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.RequirementSnapshot;
import ai.cc.chongming.review.domain.model.RequirementSnapshot.RequirementDocument;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.infrastructure.document.MarkdownRequirementParser;
import ai.cc.chongming.review.infrastructure.document.MarkdownRequirementValidator;
import ai.cc.chongming.review.infrastructure.document.RequirementSnapshotStore;
import ai.cc.chongming.review.infrastructure.document.StoredRequirementSnapshot;
import ai.cc.chongming.review.infrastructure.document.ValidatedMarkdown;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Creates immutable requirement snapshots and applies deterministic intake idempotency.
 *
 * @author wangli
 */
@Service
public class ReviewIntakeService {

    private final MarkdownRequirementValidator validator;
    private final MarkdownRequirementParser parser;
    private final RequirementSnapshotStore snapshotStore;
    private final Map<IntakeKey, ReviewIntakeResult> submissions = new HashMap<>();

    public ReviewIntakeService(
            MarkdownRequirementValidator validator,
            MarkdownRequirementParser parser,
            RequirementSnapshotStore snapshotStore) {
        this.validator = validator;
        this.parser = parser;
        this.snapshotStore = snapshotStore;
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

    /**
     * Stable deduplication key scoped to submitter, repository snapshot identity and content hash.
     *
     * @author wangli
     */
    private record IntakeKey(
            String submitter, String repositoryPath, String branch, String commit, String contentHash) {
    }
}