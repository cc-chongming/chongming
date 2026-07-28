package ai.cc.chongming.review.application;

import ai.cc.chongming.review.config.NotificationOutboxProperties;
import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.HumanGateDecision;
import ai.cc.chongming.review.domain.model.NotificationCommand;
import ai.cc.chongming.review.domain.model.NotificationDeliveryReceipt;
import ai.cc.chongming.review.domain.model.NotificationOutboxEntry;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.domain.repository.HumanGateDecisionStore;
import ai.cc.chongming.review.domain.repository.NotificationOutboxStore;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * [AIREVIEW-PLAN-011#1.5] Creates idempotent final-Gate notifications and owns retry/delivery state changes.
 *
 * @author wangli
 */
@Service
public class NotificationOutboxService implements ReviewEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationOutboxService.class);
    private static final int MAX_BACKOFF_EXPONENT = 10;

    private final NotificationOutboxStore outboxStore;
    private final HumanGateDecisionStore decisionStore;
    private final ReviewRegistry reviewRegistry;
    private final ReviewStateMachine stateMachine;
    private final ReviewEventPublisher eventPublisher;
    private final NotificationOutboxProperties properties;
    private final Clock clock;
    private final ReviewOrchestrationService orchestrationService;

    public NotificationOutboxService(
            NotificationOutboxStore outboxStore,
            HumanGateDecisionStore decisionStore,
            ReviewRegistry reviewRegistry,
            ReviewStateMachine stateMachine,
            @Lazy ReviewEventPublisher eventPublisher,
            NotificationOutboxProperties properties) {
        this(outboxStore, decisionStore, reviewRegistry, stateMachine, eventPublisher, properties, Clock.systemUTC(), null);
    }

    public NotificationOutboxService(
            NotificationOutboxStore outboxStore,
            HumanGateDecisionStore decisionStore,
            ReviewRegistry reviewRegistry,
            ReviewStateMachine stateMachine,
            @Lazy ReviewEventPublisher eventPublisher,
            NotificationOutboxProperties properties,
            Clock clock) {
        this(outboxStore, decisionStore, reviewRegistry, stateMachine, eventPublisher, properties, clock, null);
    }

    @Autowired
    public NotificationOutboxService(
            NotificationOutboxStore outboxStore,
            HumanGateDecisionStore decisionStore,
            ReviewRegistry reviewRegistry,
            ReviewStateMachine stateMachine,
            @Lazy ReviewEventPublisher eventPublisher,
            NotificationOutboxProperties properties,
            @Lazy ReviewOrchestrationService orchestrationService) {
        this(outboxStore, decisionStore, reviewRegistry, stateMachine, eventPublisher, properties, Clock.systemUTC(), orchestrationService);
    }

    public NotificationOutboxService(
            NotificationOutboxStore outboxStore,
            HumanGateDecisionStore decisionStore,
            ReviewRegistry reviewRegistry,
            ReviewStateMachine stateMachine,
            @Lazy ReviewEventPublisher eventPublisher,
            NotificationOutboxProperties properties,
            Clock clock,
            ReviewOrchestrationService orchestrationService) {
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore must not be null");
        this.decisionStore = Objects.requireNonNull(decisionStore, "decisionStore must not be null");
        this.reviewRegistry = Objects.requireNonNull(reviewRegistry, "reviewRegistry must not be null");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.orchestrationService = orchestrationService;
    }

    /**
     * Enqueues the immutable final result once per review, Gate version and channel.
     */
    public synchronized NotificationOutboxEntry enqueue(Review review, HumanGateDecision decision) {
        Objects.requireNonNull(review, "review must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        if (!review.id().equals(decision.reviewId())) {
            throw new IllegalArgumentException("decision does not belong to review");
        }
        NotificationCommand command = new NotificationCommand(
                review.id(), decision.gateVersion(), properties.channel(), properties.destination(),
                decision.result(), decision.reason(), decision.conditions(), reportUrl(review.id()));
        Optional<NotificationOutboxEntry> existing = outboxStore.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }
        NotificationOutboxEntry created = outboxStore.enqueue(NotificationOutboxEntry.pending(command, requestHash(command), clock.instant()));
        eventPublisher.publish(ReviewEventDrafts.completedCommand(
                review, ReviewEventType.NOTIFICATION_QUEUED, RoleType.DIRECTOR,
                null, null, null, null, null, 96,
                Map.of("notificationId", created.notificationId().toString(), "channel", command.channel(),
                        "gateVersion", Long.toString(command.gateVersion()))));
        return created;
    }

    /**
     * Claims and dispatches a bounded batch. Every transport failure is converted into a durable retry/dead state.
     */
    public int dispatchDue(NotificationDeliveryPort deliveryPort, int batchSize) {
        Objects.requireNonNull(deliveryPort, "deliveryPort must not be null");
        List<NotificationOutboxEntry> entries = outboxStore.claimDue(clock.instant(), batchSize);
        for (NotificationOutboxEntry entry : entries) {
            try {
                markDelivered(entry, deliveryPort.deliver(entry.command()));
            } catch (NotificationDeliveryException exception) {
                markFailure(entry, exception.code(), exception.retryable());
            } catch (RuntimeException exception) {
                markFailure(entry, "NOTIFICATION_TRANSPORT_ERROR", true);
            }
        }
        return entries.size();
    }

    public List<NotificationOutboxEntry> findByReview(ReviewId reviewId) {
        return outboxStore.findByReview(Objects.requireNonNull(reviewId, "reviewId must not be null"));
    }

    public NotificationOutboxEntry retryNow(ReviewId reviewId, UUID notificationId, long expectedVersion) {
        return retryNow(reviewId, notificationId, expectedVersion, "system");
    }

    public NotificationOutboxEntry retryNow(
            ReviewId reviewId, UUID notificationId, long expectedVersion, String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId must not be blank");
        }
        NotificationOutboxEntry entry = requireEntry(reviewId, notificationId);
        NotificationOutboxEntry retried = entry.retryNow(expectedVersion, clock.instant());
        outboxStore.save(retried);
        reviewRegistry.find(reviewId).ifPresent(review -> eventPublisher.publish(ReviewEventDrafts.completedCommand(
                review, ReviewEventType.NOTIFICATION_RETRY_REQUESTED, RoleType.DIRECTOR,
                null, null, null, null, null, 96,
                Map.of("notificationId", notificationId.toString(), "idempotencyKey", entry.command().idempotencyKey(),
                        "actor", actorId))));
        return retried;
    }
    /**
     * The event is emitted only after final Gate persistence. Listener failures are isolated from that final decision.
     */
    @Override
    public void onCommitted(ReviewEvent event) {
        if (event.type() != ReviewEventType.HUMAN_GATE_FINALIZED) {
            return;
        }
        reviewRegistry.find(event.reviewId()).ifPresent(review -> decisionStore.findLatest(event.reviewId()).ifPresent(decision -> {
            try {
                enqueue(review, decision);
            } catch (RuntimeException exception) {
                LOGGER.error("Unable to enqueue notification for finalized review {}", event.reviewId().value(), exception);
            }
        }));
    }

    private void markDelivered(NotificationOutboxEntry entry, NotificationDeliveryReceipt receipt) {
        NotificationOutboxEntry sent = entry.markSent(entry.version(), receipt, clock.instant());
        outboxStore.save(sent);
        reviewRegistry.find(entry.command().reviewId()).ifPresent(review -> {
            if (review.stage() == ReviewStage.NOTIFYING) {
                review.transitionTo(stateMachine, ReviewStage.COMPLETED);
            }
            eventPublisher.publish(ReviewEventDrafts.completedCommand(
                    review, ReviewEventType.NOTIFICATION_SENT, RoleType.DIRECTOR,
                    null, null, null, null, null, 100,
                    Map.of("notificationId", entry.notificationId().toString(), "responseCode", receipt.responseCode())));
            if (orchestrationService != null && review.stage() == ReviewStage.COMPLETED) {
                orchestrationService.releaseRuntime(review.id(), review.attemptNo())
                        .subscribe(null, failure -> LOGGER.warn(
                                "Unable to release completed runtime reviewId={} attempt={}",
                                review.id().value(), review.attemptNo(), failure));
            }
        });
    }

    private void markFailure(NotificationOutboxEntry entry, String errorCode, boolean retryable) {
        int nextAttempt = entry.attemptCount() + 1;
        boolean dead = !retryable || nextAttempt >= properties.maxAttempts();
        NotificationOutboxEntry failed = entry.markFailed(
                entry.version(), errorCode, dead, dead ? clock.instant() : nextRetryAt(nextAttempt), clock.instant());
        outboxStore.save(failed);
        reviewRegistry.find(entry.command().reviewId()).ifPresent(review -> eventPublisher.publish(ReviewEventDrafts.completedCommand(
                review, dead ? ReviewEventType.NOTIFICATION_DEAD : ReviewEventType.NOTIFICATION_FAILED,
                RoleType.DIRECTOR, null, null, null, null, null, 96,
                Map.of("notificationId", entry.notificationId().toString(), "errorCode", errorCode,
                        "attempt", Integer.toString(nextAttempt)))));
    }

    private NotificationOutboxEntry requireEntry(ReviewId reviewId, UUID notificationId) {
        NotificationOutboxEntry entry = outboxStore.find(notificationId)
                .orElseThrow(() -> new java.util.NoSuchElementException("notification does not exist"));
        if (!entry.command().reviewId().equals(reviewId)) {
            throw new java.util.NoSuchElementException("notification does not belong to review");
        }
        return entry;
    }

    private Instant nextRetryAt(int nextAttempt) {
        int exponent = Math.min(Math.max(0, nextAttempt - 1), MAX_BACKOFF_EXPONENT);
        Duration delay = properties.initialRetryDelay().multipliedBy(1L << exponent);
        return clock.instant().plus(delay);
    }

    private String reportUrl(ReviewId reviewId) {
        return "/api/reviews/" + reviewId.value() + "/report";
    }

    private String requestHash(NotificationCommand command) {
        String source = String.join("\u001f",
                command.idempotencyKey(), command.destination(), command.result().name(), command.reason(),
                String.join("\u001e", command.conditions()), command.reportUrl());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hash.append(String.format("%02x", value));
            }
            return hash.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
