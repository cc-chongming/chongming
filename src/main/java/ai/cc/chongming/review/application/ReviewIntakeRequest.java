package ai.cc.chongming.review.application;

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
        IntakeCancellation cancellation) {

    public ReviewIntakeRequest {
        Objects.requireNonNull(requirementFile, "requirementFile must not be null");
        idempotencyScope = idempotencyScope == null || idempotencyScope.isBlank()
                ? null
                : idempotencyScope.trim();
        cancellation = cancellation == null ? IntakeCancellation.neverCancelled() : cancellation;
    }

    public ReviewIntakeRequest(
            MultipartFile requirementFile,
            String repositoryPath,
            String branch,
            String commit,
            String submitter,
            boolean forceNewAttempt,
            IntakeCancellation cancellation) {
        this(requirementFile, repositoryPath, branch, commit, submitter, forceNewAttempt, null, cancellation);
    }

    public ReviewIntakeRequest(
            MultipartFile requirementFile,
            String repositoryPath,
            String branch,
            String commit,
            String submitter,
            boolean forceNewAttempt) {
        this(requirementFile, repositoryPath, branch, commit, submitter, forceNewAttempt, null,
                IntakeCancellation.neverCancelled());
    }
}
