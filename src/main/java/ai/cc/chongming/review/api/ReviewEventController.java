package ai.cc.chongming.review.api;

import ai.cc.chongming.review.application.ReviewEventService;
import ai.cc.chongming.review.application.ReviewSseProperties;
import ai.cc.chongming.review.application.ReviewSseRegistry;
import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

/**
 * [AIREVIEW-PLAN-010#1.4] Streams persisted review events and resumes from the client sequence cursor.
 *
 * @author wangli
 */
@RestController
@RequestMapping("/api/reviews/{reviewId}/events")
public class ReviewEventController {

    private final ReviewEventService eventService;
    private final ReviewSseRegistry registry;
    private final ReviewSseProperties properties;

    public ReviewEventController(
            ReviewEventService eventService,
            ReviewSseRegistry registry,
            ReviewSseProperties properties) {
        this.eventService = eventService;
        this.registry = registry;
        this.properties = properties;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable UUID reviewId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(value = "afterSequence", required = false) Long afterSequence) {
        long cursor = resolveCursor(lastEventId, afterSequence);
        ReviewSseRegistry.Subscription subscription = registry.subscribe(new ReviewId(reviewId));
        replayAll(subscription, cursor);
        registry.activate(subscription);
        return subscription.emitter();
    }

    private void replayAll(ReviewSseRegistry.Subscription subscription, long afterSequence) {
        long cursor = afterSequence;
        while (true) {
            List<ReviewEvent> page = eventService.replay(subscription.reviewId(), cursor, properties.replayBatchSize());
            registry.replay(subscription, page);
            if (page.size() < properties.replayBatchSize()) {
                return;
            }
            cursor = page.getLast().sequence();
        }
    }

    private long resolveCursor(String lastEventId, Long afterSequence) {
        Long headerCursor = lastEventId == null || lastEventId.isBlank() ? null : parseCursor(lastEventId);
        if (headerCursor != null && afterSequence != null && !headerCursor.equals(afterSequence)) {
            throw new IllegalArgumentException("Last-Event-ID and afterSequence must match when both are supplied");
        }
        long cursor = afterSequence != null ? afterSequence : headerCursor == null ? 0L : headerCursor;
        if (cursor < 0) {
            throw new IllegalArgumentException("afterSequence must not be negative");
        }
        return cursor;
    }

    private long parseCursor(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Last-Event-ID must be a non-negative integer", exception);
        }
    }
}
