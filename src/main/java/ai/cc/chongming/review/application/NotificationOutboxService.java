package ai.cc.chongming.review.application;

import ai.cc.chongming.auth.domain.User;
import ai.cc.chongming.auth.domain.UserRepository;
import ai.cc.chongming.review.config.ChaoxingNotificationProperties;
import ai.cc.chongming.review.config.NotificationOutboxProperties;
import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.HumanGateDecision;
import ai.cc.chongming.review.domain.model.NotificationCommand;
import ai.cc.chongming.review.domain.model.NotificationDeliveryReceipt;
import ai.cc.chongming.review.domain.model.NotificationOutboxEntry;
import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewStage;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.domain.protocol.ReviewStateMachine;
import ai.cc.chongming.review.domain.repository.HumanGateDecisionStore;
import ai.cc.chongming.review.domain.repository.NotificationOutboxStore;
import ai.cc.chongming.review.domain.repository.RequirementRepository;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.infrastructure.notification.NotificationDeliveryRouter;
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
    private final UserRepository userRepository;
    private final RequirementRepository requirementRepository;
    private final ChaoxingNotificationProperties chaoxingProperties;

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

    public NotificationOutboxService(
            NotificationOutboxStore outboxStore,
            HumanGateDecisionStore decisionStore,
            ReviewRegistry reviewRegistry,
            ReviewStateMachine stateMachine,
            @Lazy ReviewEventPublisher eventPublisher,
            NotificationOutboxProperties properties,
            Clock clock,
            ReviewOrchestrationService orchestrationService) {
        this(outboxStore, decisionStore, reviewRegistry, stateMachine, eventPublisher, properties, clock,
                orchestrationService, null, null);
    }

    /**
     * [AIREVIEW-PLAN-030] Full wiring carrying the user directory so the transition notification
     * matrix can resolve per-recipient mail destinations.
     */
    @Autowired
    public NotificationOutboxService(
            NotificationOutboxStore outboxStore,
            HumanGateDecisionStore decisionStore,
            ReviewRegistry reviewRegistry,
            ReviewStateMachine stateMachine,
            @Lazy ReviewEventPublisher eventPublisher,
            NotificationOutboxProperties properties,
            @Lazy ReviewOrchestrationService orchestrationService,
            UserRepository userRepository,
            RequirementRepository requirementRepository,
            org.springframework.beans.factory.ObjectProvider<ChaoxingNotificationProperties> chaoxingProperties) {
        this(outboxStore, decisionStore, reviewRegistry, stateMachine, eventPublisher, properties,
                Clock.systemUTC(), orchestrationService, userRepository, requirementRepository,
                chaoxingProperties.getIfAvailable());
    }

    public NotificationOutboxService(
            NotificationOutboxStore outboxStore,
            HumanGateDecisionStore decisionStore,
            ReviewRegistry reviewRegistry,
            ReviewStateMachine stateMachine,
            ReviewEventPublisher eventPublisher,
            NotificationOutboxProperties properties,
            Clock clock,
            ReviewOrchestrationService orchestrationService,
            UserRepository userRepository,
            RequirementRepository requirementRepository) {
        this(outboxStore, decisionStore, reviewRegistry, stateMachine, eventPublisher, properties, clock,
                orchestrationService, userRepository, requirementRepository, null);
    }

    public NotificationOutboxService(
            NotificationOutboxStore outboxStore,
            HumanGateDecisionStore decisionStore,
            ReviewRegistry reviewRegistry,
            ReviewStateMachine stateMachine,
            ReviewEventPublisher eventPublisher,
            NotificationOutboxProperties properties,
            Clock clock,
            ReviewOrchestrationService orchestrationService,
            UserRepository userRepository,
            RequirementRepository requirementRepository,
            ChaoxingNotificationProperties chaoxingProperties) {
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore must not be null");
        this.decisionStore = Objects.requireNonNull(decisionStore, "decisionStore must not be null");
        this.reviewRegistry = Objects.requireNonNull(reviewRegistry, "reviewRegistry must not be null");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.orchestrationService = orchestrationService;
        this.userRepository = userRepository;
        this.requirementRepository = requirementRepository;
        this.chaoxingProperties = chaoxingProperties;
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
                review.id(), decision.gateVersion(), properties.channel(), gateDestination(decision),
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
        List<NotificationOutboxEntry> entries = outboxStore.findByReview(Objects.requireNonNull(reviewId, "reviewId must not be null"));
        if (!entries.isEmpty()) {
            return entries;
        }
        // [AIREVIEW-PLAN-011#1.5] The in-memory outbox is cleared by a restart; rebuild the final
        // Gate entry from the persisted decision so the status panel self-heals.
        return recoverFinalGateEntry(reviewId).map(List::of).orElse(entries);
    }

    public NotificationOutboxEntry retryNow(ReviewId reviewId, UUID notificationId, long expectedVersion) {
        return retryNow(reviewId, notificationId, expectedVersion, "system");
    }

    public NotificationOutboxEntry retryNow(
            ReviewId reviewId, UUID notificationId, long expectedVersion, String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId must not be blank");
        }
        NotificationOutboxEntry entry;
        try {
            entry = requireEntry(reviewId, notificationId);
        } catch (java.util.NoSuchElementException missing) {
            // [AIREVIEW-PLAN-011#1.5] The in-memory outbox is cleared by a restart; recover the
            // final Gate entry from the persisted decision so retries keep working.
            entry = recoverFinalGateEntry(reviewId).orElseThrow(() -> missing);
            publishRetryRequested(reviewId, entry, actorId);
            return entry;
        }
        NotificationOutboxEntry retried = entry.retryNow(expectedVersion, clock.instant());
        outboxStore.save(retried);
        publishRetryRequested(reviewId, entry, actorId);
        return retried;
    }

    private void publishRetryRequested(ReviewId reviewId, NotificationOutboxEntry entry, String actorId) {
        reviewRegistry.find(reviewId).ifPresent(review -> eventPublisher.publish(ReviewEventDrafts.completedCommand(
                review, ReviewEventType.NOTIFICATION_RETRY_REQUESTED, RoleType.DIRECTOR,
                null, null, null, null, null, 96,
                Map.of("notificationId", entry.notificationId().toString(), "idempotencyKey", entry.command().idempotencyKey(),
                        "actor", actorId))));
    }

    /**
     * [AIREVIEW-PLAN-011#1.5] Re-materializes the idempotent final-Gate entry for a review that is
     * still NOTIFYING after a restart wiped the in-memory outbox.
     */
    private java.util.Optional<NotificationOutboxEntry> recoverFinalGateEntry(ReviewId reviewId) {
        return reviewRegistry.find(reviewId)
                .filter(review -> review.stage() == ReviewStage.NOTIFYING)
                .flatMap(review -> decisionStore.findLatest(reviewId)
                        .map(decision -> enqueue(review, decision)));
    }
    /**
     * [AIREVIEW-PLAN-030] The mail recipient is the deciding reviewer's mailbox as registered in the
     * user directory (sender stays configuration-driven); the configured destination only serves as
     * a fallback for accounts without a stored email.
     */
    private String gateDestination(HumanGateDecision decision) {
        if (userRepository != null
                && NotificationDeliveryRouter.MAIL_CHANNEL.equalsIgnoreCase(properties.channel())
                && decision.reviewerId() != null && !decision.reviewerId().isBlank()) {
            String email = userRepository.findByUsername(decision.reviewerId().trim())
                    .map(User::email)
                    .filter(value -> value != null && !value.isBlank())
                    .orElse(null);
            if (email != null) {
                return email.trim();
            }
        }
        return properties.destination();
    }

    /**
     * The event is emitted only after final Gate persistence. Listener failures are isolated from that final decision.
     */
    @Override
    public void onCommitted(ReviewEvent event) {
        if (event.type() == ReviewEventType.HUMAN_GATE_FINALIZED) {
            reviewRegistry.find(event.reviewId()).ifPresent(review -> decisionStore.findLatest(event.reviewId()).ifPresent(decision -> {
                try {
                    enqueue(review, decision);
                } catch (RuntimeException exception) {
                    LOGGER.error("Unable to enqueue notification for finalized review {}", event.reviewId().value(), exception);
                }
            }));
            return;
        }
        if (MATRIX_EVENTS.contains(event.type())) {
            try {
                enqueueMatrix(event);
            } catch (RuntimeException exception) {
                LOGGER.error("Unable to enqueue matrix notification for event {} review {}",
                        event.type(), event.reviewId().value(), exception);
            }
        }
    }

    /** [AIREVIEW-PLAN-030] Transition events driving the notification matrix (N1-N5, N7, N8). */
    private static final java.util.Set<ReviewEventType> MATRIX_EVENTS = java.util.Set.of(
            ReviewEventType.HUMAN_REVIEW_REQUIRED,
            ReviewEventType.TASK_ASSIGNED,
            ReviewEventType.TASK_HANDOFF,
            ReviewEventType.TASK_PAUSED,
            ReviewEventType.TASK_RESUMED,
            ReviewEventType.TASK_SUBMITTED_FOR_ACCEPTANCE,
            ReviewEventType.TASK_ACCEPTED,
            ReviewEventType.TASK_REJECTED,
            ReviewEventType.TASK_CANCELLED);

    /**
     * [AIREVIEW-PLAN-030] Enqueues one command per recipient and channel; recipients without any
     * contact channel still receive a LOCAL entry so the outbox panel keeps a full audit trail.
     */
    private void enqueueMatrix(ReviewEvent event) {
        if (userRepository == null) {
            return;
        }
        java.util.LinkedHashSet<String> recipients = resolveRecipients(event);
        if (recipients.isEmpty()) {
            return;
        }
        String templateKey = templateFor(event.type());
        String title = titleFor(event);
        // [AIREVIEW-PLAN-109] Carry the task info card facts (may be null for events without a
        // task payload, e.g. HUMAN_REVIEW_REQUIRED); the command constructor normalizes blanks.
        java.util.Map<String, String> payload = event.payload();
        String objectTitle = payload.get("taskTitle");
        String objectSubtitle = payload.get("requirementTitle");
        String objectStatus = payload.get("status");
        String objectHolder = payload.get("holder");
        // [AIREVIEW-PLAN-110#1] 需求详情按钮深链需要需求 id。
        String requirementId = payload.get("requirementId");
        // [AIREVIEW-PLAN-115#1] 非任务事件（如待人工决策）payload 无需求 id：
        // 按 reviewId 反查需求，邮件才能与流转邮件一样带「查看需求详情」按钮。
        if (requirementId == null || requirementId.isBlank()) {
            Requirement boundRequirement = requirementRepository == null
                    ? null : requirementRepository.findByReviewId(event.reviewId()).orElse(null);
            if (boundRequirement != null) {
                requirementId = boundRequirement.id().value().toString();
                if (objectSubtitle == null || objectSubtitle.isBlank()) {
                    objectSubtitle = boundRequirement.title();
                }
            }
        }
        for (String username : recipients) {
            User user = userRepository.findByUsername(username).orElse(null);
            if (user == null) {
                continue;
            }
            java.util.List<String[]> channels = new java.util.ArrayList<>();
            if (user.email() != null) {
                channels.add(new String[] {NotificationDeliveryRouter.MAIL_CHANNEL, user.email()});
            }
            // [AIREVIEW-PLAN-030] Multi-endpoint fan-out: when the Chaoxing channel is enabled and the
            // user carries a numeric company uid, enqueue a parallel chaoxing entry alongside mail.
            String chaoxingUid = chaoxingUid(user);
            if (chaoxingUid != null) {
                channels.add(new String[] {NotificationDeliveryRouter.CHAOXING_CHANNEL, chaoxingUid});
            }
            if (channels.isEmpty()) {
                channels.add(new String[] {"local", username});
            }
            for (String[] channel : channels) {
                NotificationCommand command = NotificationCommand.forEvent(
                        event.reviewId(), event.type().name(), event.sequence(),
                        channel[0], channel[1], username, templateKey, title,
                        objectTitle, objectSubtitle, objectStatus, objectHolder, requirementId);
                if (outboxStore.findByIdempotencyKey(command.idempotencyKey()).isEmpty()) {
                    outboxStore.enqueue(NotificationOutboxEntry.pending(command, requestHash(command), clock.instant()));
                }
            }
        }
    }

    /**
     * Returns the recipient's numeric Chaoxing uid when the channel is enabled and the user's
     * company uid parses as an integer; otherwise {@code null} so no chaoxing entry is enqueued.
     */
    private String chaoxingUid(User user) {
        if (chaoxingProperties == null || !chaoxingProperties.enabled()) {
            return null;
        }
        String uid = user.companyUid();
        if (uid == null || uid.isBlank()) {
            return null;
        }
        try {
            Integer.valueOf(uid.trim());
            return uid.trim();
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private java.util.LinkedHashSet<String> resolveRecipients(ReviewEvent event) {
        java.util.LinkedHashSet<String> recipients = new java.util.LinkedHashSet<>();
        java.util.Map<String, String> payload = event.payload();
        String creator = resolveCreator(event, payload);
        switch (event.type()) {
            case HUMAN_REVIEW_REQUIRED -> {
                if (creator != null) {
                    recipients.add(creator);
                }
                recipients.addAll(adminUsernames());
            }
            case TASK_ASSIGNED, TASK_HANDOFF -> addIfPresent(recipients, payload.get("to"));
            case TASK_SUBMITTED_FOR_ACCEPTANCE -> addIfPresent(recipients, creator);
            case TASK_ACCEPTED, TASK_REJECTED -> addIfPresent(recipients, payload.get("holder"));
            case TASK_PAUSED, TASK_RESUMED -> {
                addIfPresent(recipients, creator);
                addIfPresent(recipients, payload.get("holder"));
                recipients.addAll(adminUsernames());
            }
            case TASK_CANCELLED -> {
                addIfPresent(recipients, payload.get("holder"));
                addIfPresent(recipients, creator);
            }
            default -> {
            }
        }
        return recipients;
    }

    private String resolveCreator(ReviewEvent event, java.util.Map<String, String> payload) {
        if (requirementRepository == null) {
            return null;
        }
        String requirementIdText = payload.get("requirementId");
        try {
            if (requirementIdText != null) {
                return requirementRepository.findById(new RequirementId(java.util.UUID.fromString(requirementIdText)))
                        .map(ai.cc.chongming.review.domain.model.Requirement::creatorId)
                        .orElse(null);
            }
            return requirementRepository.findByReviewId(event.reviewId())
                    .map(ai.cc.chongming.review.domain.model.Requirement::creatorId)
                    .orElse(null);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private java.util.List<String> adminUsernames() {
        return userRepository.findAll().stream()
                .filter(view -> "ADMIN".equalsIgnoreCase(view.role()))
                .map(ai.cc.chongming.auth.domain.UserRepository.UserView::username)
                .toList();
    }

    private static void addIfPresent(java.util.Set<String> recipients, String username) {
        if (username != null && !username.isBlank()) {
            recipients.add(username.trim());
        }
    }

    private static String templateFor(ReviewEventType type) {
        return switch (type) {
            case HUMAN_REVIEW_REQUIRED -> "ai-review-awaiting-decision";
            case TASK_ASSIGNED -> "task-assigned";
            case TASK_HANDOFF -> "task-handoff";
            case TASK_PAUSED -> "task-paused";
            case TASK_RESUMED -> "task-resumed";
            case TASK_SUBMITTED_FOR_ACCEPTANCE -> "task-submitted-acceptance";
            case TASK_ACCEPTED -> "task-accepted";
            case TASK_REJECTED -> "task-rejected";
            case TASK_CANCELLED -> "task-cancelled";
            default -> "transition";
        };
    }

    private static String titleFor(ReviewEvent event) {
        return switch (event.type()) {
            case HUMAN_REVIEW_REQUIRED -> "AI 评审完成，待人工决策";
            case TASK_ASSIGNED -> "新开发任务已指派给你";
            case TASK_HANDOFF -> "任务流转给你，请接续开发";
            case TASK_PAUSED -> "任务已暂停：" + event.payload().getOrDefault("note", "");
            case TASK_RESUMED -> "任务已恢复开发";
            case TASK_SUBMITTED_FOR_ACCEPTANCE -> "任务已提交验收，请验收";
            case TASK_ACCEPTED -> "验收通过";
            case TASK_REJECTED -> "验收打回：" + event.payload().getOrDefault("note", "");
            case TASK_CANCELLED -> "任务已关闭：" + event.payload().getOrDefault("note", "");
            default -> "任务流转通知";
        };
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
        // [AIREVIEW-PLAN-030] Matrix transition commands carry no Gate result; the placeholder
        // keeps hashing stable while the idempotency key already scopes them per event, recipient
        // and channel.
        String resultPart = command.result() == null ? "-" : command.result().name();
        String source = String.join("\u001f",
                command.idempotencyKey(), command.destination(), resultPart, command.reason(),
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
