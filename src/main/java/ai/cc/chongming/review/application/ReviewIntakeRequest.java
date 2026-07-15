package ai.cc.chongming.review.application;

import java.util.Objects;
import org.springframework.web.multipart.MultipartFile;

/**
 * Multipart input required to create or replay a Markdown review intake request.
 *
 * @author wangli
 */
public record ReviewIntakeRequest(
        MultipartFile requirementFile,
        String repositoryPath,
        String branch,
        String commit,
        String submitter,
        boolean forceNewAttempt,
        IntakeCancellation cancellation) {

    public ReviewIntakeRequest {
        Objects.requireNonNull(requirementFile, "requirementFile must not be null");
        cancellation = cancellation == null ? IntakeCancellation.neverCancelled() : cancellation;
    }

    public ReviewIntakeRequest(
            MultipartFile requirementFile,
            String repositoryPath,
            String branch,
            String commit,
            String submitter,
            boolean forceNewAttempt) {
        this(requirementFile, repositoryPath, branch, commit, submitter, forceNewAttempt,
                IntakeCancellation.neverCancelled());
    }
}
