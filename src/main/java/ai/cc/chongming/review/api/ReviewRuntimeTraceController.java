package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.ReviewRuntimeTraceRegistry;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * [AIREVIEW-PLAN-017#3.1] Streams current-attempt, bounded AG-UI runtime observations.
 *
 * @author wangli
 */
@RestController
@RequestMapping("/api/reviews/{reviewId}/attempts/{attemptNo}/runtime/ag-ui")
public class ReviewRuntimeTraceController {

    private final ReviewRuntimeTraceRegistry traceRegistry;

    public ReviewRuntimeTraceController(ReviewRuntimeTraceRegistry traceRegistry) {
        this.traceRegistry = traceRegistry;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable UUID reviewId,
            @PathVariable int attemptNo,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        ReviewRuntimeTraceRegistry.Subscription subscription = traceRegistry.subscribe(
                new ReviewId(reviewId), attemptNo, resolveCursor(lastEventId));
        traceRegistry.activate(subscription);
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
}
