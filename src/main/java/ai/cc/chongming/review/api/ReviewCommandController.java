package ai.cc.chongming.review.api;

import ai.cc.chongming.review.api.dto.CreateReviewResponse;
import ai.cc.chongming.review.application.ReviewIntakeRequest;
import ai.cc.chongming.review.application.ReviewIntakeResult;
import ai.cc.chongming.review.application.ReviewIntakeService;
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

    public ReviewCommandController(ReviewIntakeService reviewIntakeService) {
        this.reviewIntakeService = reviewIntakeService;
    }

    /**
     * Creates a review request from one Markdown document and one repository identity.
     *
     * @param requirementFile submitted UTF-8 Markdown file
     * @param repositoryPath repository path supplied by the caller
     * @param branch optional repository branch
     * @param commit optional repository commit
     * @param submitter caller identity
     * @param forceNewAttempt whether an otherwise identical request creates the next attempt
     * @return accepted review and immutable snapshot identity
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CreateReviewResponse> createReview(
            @RequestPart("requirementFile") MultipartFile requirementFile,
            @RequestParam("repositoryPath") String repositoryPath,
            @RequestParam(value = "branch", required = false) String branch,
            @RequestParam(value = "commit", required = false) String commit,
            @RequestParam("submitter") String submitter,
            @RequestParam(value = "forceNewAttempt", defaultValue = "false") boolean forceNewAttempt) {
        ReviewIntakeResult result = reviewIntakeService.intake(new ReviewIntakeRequest(
                requirementFile, repositoryPath, branch, commit, submitter, forceNewAttempt));
        CreateReviewResponse response = new CreateReviewResponse(
                result.snapshot().reviewId().value(),
                result.snapshot().attemptNo(),
                result.snapshot().contentHash(),
                "/api/reviews/" + result.snapshot().reviewId().value(),
                result.reused());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
