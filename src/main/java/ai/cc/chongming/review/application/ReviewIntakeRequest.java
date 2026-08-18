package ai.cc.chongming.review.application;

import java.util.Objects;

/**
 * Multipart input required to create or replay a Markdown review intake request.
 * [AIREVIEW-PLAN-025] The requirement Markdown travels as a uniform {@link IntakeDocument},
 * produced either from an uploaded {@code .md} part or from typed text.
 *
 * @author zyj
 */
public record ReviewIntakeRequest(
        IntakeDocument document,
        String repositoryPath,
        String branch,
        String commit,
        String submitter,
        boolean forceNewAttempt,
        String idempotencyScope,
        IntakeCancellation cancellation,
        ai.cc.chongming.review.domain.model.RemoteRepositorySource remoteSource) {

    public ReviewIntakeRequest {
        Objects.requireNonNull(document, "document must not be null");
        idempotencyScope = idempotencyScope == null || idempotencyScope.isBlank()
                ? null
                : idempotencyScope.trim();
        cancellation = cancellation == null ? IntakeCancellation.neverCancelled() : cancellation;
    }

    /** [AIREVIEW-PLAN-029] Backward-compatible intake without an online repository source. */
    public ReviewIntakeRequest(
            IntakeDocument document,
            String repositoryPath,
            String branch,
            String commit,
            String submitter,
            boolean forceNewAttempt,
            String idempotencyScope,
            IntakeCancellation cancellation) {
        this(document, repositoryPath, branch, commit, submitter, forceNewAttempt, idempotencyScope,
                cancellation, null);
    }

    public ReviewIntakeRequest(
            IntakeDocument document,
            String repositoryPath,
            String branch,
            String commit,
            String submitter,
            boolean forceNewAttempt,
            IntakeCancellation cancellation) {
        this(document, repositoryPath, branch, commit, submitter, forceNewAttempt, null, cancellation, null);
    }

    public ReviewIntakeRequest(
            IntakeDocument document,
            String repositoryPath,
            String branch,
            String commit,
            String submitter,
            boolean forceNewAttempt) {
        this(document, repositoryPath, branch, commit, submitter, forceNewAttempt, null,
                IntakeCancellation.neverCancelled(), null);
    }
}
