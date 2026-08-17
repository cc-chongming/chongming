package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.model.RemoteRepositorySource;
import java.util.Objects;

import org.springframework.web.multipart.MultipartFile;

/**
 * Multipart input required to create or replay a Markdown review intake request.
 *
 * @author zyj
 */
public record ReviewIntakeRequest(
        MultipartFile requirementFile,
        String repositoryPath,
        String branch,
        String commit,
        String submitter,
        boolean forceNewAttempt,
        String idempotencyScope,
        IntakeCancellation cancellation,
        RemoteRepositorySource remoteSource) {

    public ReviewIntakeRequest {
        Objects.requireNonNull(requirementFile, "requirementFile must not be null");
        idempotencyScope = idempotencyScope == null || idempotencyScope.isBlank()
                ? null
                : idempotencyScope.trim();
        cancellation = cancellation == null ? IntakeCancellation.neverCancelled() : cancellation;
    }

    /** [AIREVIEW-PLAN-029] Backward-compatible intake without an online repository source. */
    public ReviewIntakeRequest(
            MultipartFile requirementFile,
            String repositoryPath,
            String branch,
            String commit,
            String submitter,
            boolean forceNewAttempt,
            String idempotencyScope,
            IntakeCancellation cancellation) {
        this(requirementFile, repositoryPath, branch, commit, submitter, forceNewAttempt, idempotencyScope,
                cancellation, null);
    }

    public ReviewIntakeRequest(
            MultipartFile requirementFile,
            String repositoryPath,
            String branch,
            String commit,
            String submitter,
            boolean forceNewAttempt,
            IntakeCancellation cancellation) {
        this(requirementFile, repositoryPath, branch, commit, submitter, forceNewAttempt, null, cancellation, null);
    }

    public ReviewIntakeRequest(
            MultipartFile requirementFile,
            String repositoryPath,
            String branch,
            String commit,
            String submitter,
            boolean forceNewAttempt) {
        this(requirementFile, repositoryPath, branch, commit, submitter, forceNewAttempt, null,
                IntakeCancellation.neverCancelled(), null);
    }
}
