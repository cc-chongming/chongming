package ai.cc.chongming.review.api;

import ai.cc.chongming.review.api.dto.CreateReviewResponse;
import ai.cc.chongming.review.application.IntakeDocument;
import ai.cc.chongming.review.application.RemoteTokenCipher;
import ai.cc.chongming.review.application.ReviewIntakeException;
import ai.cc.chongming.review.application.ReviewIntakeRequest;
import ai.cc.chongming.review.application.ReviewIntakeResult;
import ai.cc.chongming.review.application.ReviewIntakeService;
import ai.cc.chongming.review.domain.model.RemoteRepositorySource;
import ai.cc.chongming.review.infrastructure.repository.RemoteRepositoryUrlValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Accepts Markdown requirement documents and creates asynchronous review intake requests.
 *
 * @author wangli
 */
@Validated
@RestController
@RequestMapping("/api/reviews")
public class ReviewCommandController {

    private final ReviewIntakeService reviewIntakeService;
    private final RemoteTokenCipher remoteTokenCipher;
    private final RemoteRepositoryUrlValidator remoteUrlValidator;

    public ReviewCommandController(ReviewIntakeService reviewIntakeService) {
        this(reviewIntakeService, null, null);
    }

    @Autowired
    public ReviewCommandController(
            ReviewIntakeService reviewIntakeService,
            RemoteTokenCipher remoteTokenCipher,
            RemoteRepositoryUrlValidator remoteUrlValidator) {
        this.reviewIntakeService = reviewIntakeService;
        this.remoteTokenCipher = remoteTokenCipher;
        this.remoteUrlValidator = remoteUrlValidator;
    }

    /**
     * Creates a review request from one Markdown document and one repository identity.
     * [AIREVIEW-PLAN-025] The Markdown may arrive either as an uploaded {@code .md} part or as the
     * typed {@code requirementText} parameter; exactly one of the two must be present.
     * [AIREVIEW-PLAN-029] Alternatively accepts an online repository source; the token is
     * encrypted before the intake snapshot is written and never echoed afterwards.
     *
     * @param requirementFile submitted UTF-8 Markdown file (optional when requirementText is set)
     * @param requirementText typed UTF-8 Markdown (optional when requirementFile is set)
     * @param repositoryPath repository path supplied by the caller (optional when remoteUrl is set)
     * @param branch optional repository branch
     * @param commit optional repository commit
     * @param submitter caller identity
     * @param forceNewAttempt whether an otherwise identical request creates the next attempt
     * @param remoteUrl optional online repository URL supplied by the caller
     * @param remoteRef optional online repository branch
     * @param remoteToken optional online repository access token (write-only)
     * @return accepted review and immutable snapshot identity
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CreateReviewResponse> createReview(
            @RequestPart(value = "requirementFile", required = false) MultipartFile requirementFile,
            @RequestParam(value = "requirementText", required = false) String requirementText,
            @RequestParam(value = "repositoryPath", required = false) String repositoryPath,
            @RequestParam(value = "branch", required = false) String branch,
            @RequestParam(value = "commit", required = false) String commit,
            @RequestParam("submitter") String submitter,
            @RequestParam(value = "forceNewAttempt", defaultValue = "false") boolean forceNewAttempt,
            @RequestParam(value = "remoteUrl", required = false) String remoteUrl,
            @RequestParam(value = "remoteRef", required = false) String remoteRef,
            @RequestParam(value = "remoteToken", required = false) String remoteToken) {
        RemoteRepositorySource remoteSource = resolveRemoteSource(remoteUrl, remoteRef, remoteToken);
        if (remoteSource == null && (repositoryPath == null || repositoryPath.isBlank())) {
            throw ReviewIntakeException.badRequest("REMOTE_SOURCE_INVALID", "请选择配置仓库或填写线上仓库地址");
        }
        if (remoteSource != null && repositoryPath != null && !repositoryPath.isBlank()) {
            throw ReviewIntakeException.badRequest("REMOTE_SOURCE_INVALID", "配置仓库与线上仓库只能二选一");
        }
        ReviewIntakeResult result = reviewIntakeService.intake(new ReviewIntakeRequest(
                IntakeDocument.from(requirementFile, requirementText),
                repositoryPath, branch, commit, submitter, forceNewAttempt, null,
                null, remoteSource));
        CreateReviewResponse response = new CreateReviewResponse(
                result.snapshot().reviewId().value(),
                result.snapshot().attemptNo(),
                result.snapshot().contentHash(),
                "/api/reviews/" + result.snapshot().reviewId().value(),
                result.reused());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /** [AIREVIEW-PLAN-029] Validates and encrypts one caller-supplied online repository source. */
    private RemoteRepositorySource resolveRemoteSource(String remoteUrl, String remoteRef, String remoteToken) {
        String url = remoteUrl == null ? "" : remoteUrl.trim();
        if (url.isEmpty()) {
            return null;
        }
        if (remoteTokenCipher == null || remoteUrlValidator == null) {
            throw ReviewIntakeException.badRequest("REMOTE_SOURCE_INVALID", "线上仓库接入能力在当前环境不可用");
        }
        try {
            remoteUrlValidator.requireSafe(url);
        } catch (ai.cc.chongming.review.application.RepositoryAccessException exception) {
            throw ReviewIntakeException.badRequest("REMOTE_SOURCE_INVALID", "线上仓库地址不合法或不被允许");
        }
        String ref = remoteRef == null || remoteRef.isBlank() ? null : remoteRef.trim();
        String token = remoteToken == null ? null : remoteToken.trim();
        String encryptedToken;
        try {
            encryptedToken = token == null || token.isEmpty() ? null : remoteTokenCipher.encrypt(token);
        } catch (RuntimeException exception) {
            throw ReviewIntakeException.badRequest("REMOTE_SOURCE_INVALID", "线上仓库令牌无法保存");
        }
        return new RemoteRepositorySource(url, ref, encryptedToken);
    }
}
