package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.ContextScoutPreviewService;
import ai.cc.chongming.review.application.ReviewRuntimeTraceRegistry;
import ai.cc.chongming.review.config.ReviewDiagnosticsProperties;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Browser-facing diagnostic entry point for an isolated Context Scout run.
 *
 * @author wangli
 */
@Validated
@RestController
@RequestMapping("/api/reviews/{reviewId}/attempts/{attemptNo}/scout-previews")
public class ContextScoutPreviewController {

    private final ContextScoutPreviewService previewService;
    private final ReviewDiagnosticsProperties diagnosticsProperties;

    public ContextScoutPreviewController(
            ContextScoutPreviewService previewService,
            ReviewDiagnosticsProperties diagnosticsProperties) {
        this.previewService = previewService;
        this.diagnosticsProperties = diagnosticsProperties;
    }

    @PostMapping
    public ResponseEntity<ContextScoutPreviewService.PreviewStartResult> start(
            @PathVariable UUID reviewId,
            @PathVariable int attemptNo,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @Valid @RequestBody ScoutPreviewRequest request) {
        requirePreviewEnabled();
        String effectiveTraceId = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(previewService.start(
                new ReviewId(reviewId), attemptNo, request.userId(), effectiveTraceId));
    }

    @GetMapping("/{previewId}")
    public ContextScoutPreviewService.PreviewStatus status(
            @PathVariable UUID reviewId, @PathVariable int attemptNo, @PathVariable String previewId) {
        requirePreviewEnabled();
        return previewService.status(new ReviewId(reviewId), attemptNo, previewId);
    }

    @GetMapping(value = "/{previewId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable UUID reviewId,
            @PathVariable int attemptNo,
            @PathVariable String previewId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        requirePreviewEnabled();
        ReviewRuntimeTraceRegistry.Subscription subscription = previewService.subscribe(
                new ReviewId(reviewId), attemptNo, previewId, resolveCursor(lastEventId));
        previewService.activate(subscription);
        return subscription.emitter();
    }

    private long resolveCursor(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0L;
        }
        try {
            long cursor = Long.parseLong(lastEventId);
            if (cursor < 0) {
                throw new IllegalArgumentException("Last-Event-ID must be a non-negative integer");
            }
            return cursor;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Last-Event-ID must be a non-negative integer", exception);
        }
    }

    private void requirePreviewEnabled() {
        if (!diagnosticsProperties.contextScoutPreviewEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    public record ScoutPreviewRequest(@NotBlank String userId) {
    }
}
