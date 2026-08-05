package ai.cc.chongming.review.application;

import ai.cc.chongming.review.domain.event.ReviewEvent;
import ai.cc.chongming.review.domain.event.ReviewEventType;
import ai.cc.chongming.review.domain.model.HumanGateDecision;
import ai.cc.chongming.review.domain.model.HumanReviewItem;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.model.ReviewReport;
import ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceId;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.HumanGateDecisionStore;
import ai.cc.chongming.review.domain.repository.HumanReviewItemStore;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.domain.repository.ReviewReportStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * [AIREVIEW-PLAN-011#1.4] Creates immutable public report snapshots after a human Gate is finalized.
 *
 * @author wangli
 */
@Service
public class ReviewReportService implements ReviewEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewReportService.class);
    private static final int PLAN_PAGE_SIZE = 500;
    private static final int MAX_PLAN_PAGES = 100;

    private final ReviewReportStore reportStore;
    private final ReviewQueryService queryService;
    private final HumanReviewItemStore itemStore;
    private final HumanGateDecisionStore decisionStore;
    private final ReviewRegistry reviewRegistry;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public ReviewReportService(
            ReviewReportStore reportStore,
            ReviewQueryService queryService,
            HumanReviewItemStore itemStore,
            HumanGateDecisionStore decisionStore,
            ReviewRegistry reviewRegistry,
            ObjectMapper objectMapper) {
        this(reportStore, queryService, itemStore, decisionStore, reviewRegistry, objectMapper, Clock.systemUTC());
    }

    public ReviewReportService(
            ReviewReportStore reportStore,
            ReviewQueryService queryService,
            HumanReviewItemStore itemStore,
            HumanGateDecisionStore decisionStore,
            ReviewRegistry reviewRegistry,
            ObjectMapper objectMapper,
            Clock clock) {
        this.reportStore = Objects.requireNonNull(reportStore, "reportStore must not be null");
        this.queryService = Objects.requireNonNull(queryService, "queryService must not be null");
        this.itemStore = Objects.requireNonNull(itemStore, "itemStore must not be null");
        this.decisionStore = Objects.requireNonNull(decisionStore, "decisionStore must not be null");
        this.reviewRegistry = Objects.requireNonNull(reviewRegistry, "reviewRegistry must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Generates a new report version from stable, public facts only.
     */
    public synchronized ReviewReport generate(Review review) {
        Objects.requireNonNull(review, "review must not be null");
        HumanGateDecision finalDecision = decisionStore.findLatest(review.id())
                .orElseThrow(() -> new IllegalStateException("a final human Gate decision is required before reporting"));
        ReviewQueryService.ReviewSummary summary = queryService.findSummary(review.id())
                .orElseThrow(() -> new IllegalStateException("review summary is unavailable"));
        List<HumanReviewItem> humanItems = itemStore.findByReview(review.id(), null, null);
        List<ReviewQueryService.DebateView> debates = queryService.findDebates(review.id());
        List<ReviewQueryService.ClaimView> claims = queryService.findClaims(review.id());
        claims = claims == null ? List.of() : List.copyOf(claims);
        ReportContent content = new ReportContent(
                summary,
                findAllPlans(review.id()),
                review.roleActivations().stream()
                        .map(activation -> new RoleView(
                                activation.roleType().name(), activation.agentLabel(), activation.initialReviewCompleted()))
                        .toList(),
                claims,
                debates,
                humanItems.stream().map(this::toHumanItemView).toList(),
                decisionStore.findVersions(review.id()).stream().map(this::toGateView).toList(),
                findEvidenceLinks(review.id(), claims, debates, humanItems));
        String contentJson = writeJson(content);
        ReviewReport report = new ReviewReport(
                UUID.randomUUID(),
                review.id(),
                reportStore.findLatest(review.id()).map(existing -> existing.reportVersion() + 1L).orElse(1L),
                finalDecision.gateVersion(),
                sha256(contentJson),
                contentJson,
                toMarkdown(content),
                clock.instant());
        reportStore.append(report);
        return report;
    }

    public java.util.Optional<ReviewReport> find(ReviewId reviewId, Long reportVersion) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        return reportVersion == null ? reportStore.findLatest(reviewId) : reportStore.findVersion(reviewId, reportVersion);
    }

    public List<ReportVersionView> findVersions(ReviewId reviewId) {
        return reportStore.findVersions(Objects.requireNonNull(reviewId, "reviewId must not be null")).stream()
                .map(report -> new ReportVersionView(
                        report.reportVersion(), report.gateVersion(), report.contentHash(), report.createdAt().toString()))
                .toList();
    }

    /**
     * Report generation is deliberately best-effort: a report failure must never roll back the final Gate.
     */
    @Override
    public void onCommitted(ReviewEvent event) {
        if (event.type() != ReviewEventType.HUMAN_GATE_FINALIZED) {
            return;
        }
        reviewRegistry.find(event.reviewId()).ifPresent(review -> {
            try {
                generate(review);
            } catch (RuntimeException exception) {
                LOGGER.error("Unable to generate report for finalized review {}", event.reviewId().value(), exception);
            }
        });
    }

    private List<ReviewQueryService.EventView> findAllPlans(ReviewId reviewId) {
        List<ReviewQueryService.EventView> plans = new java.util.ArrayList<>();
        long cursor = 0L;
        for (int pageNo = 0; pageNo < MAX_PLAN_PAGES; pageNo++) {
            ReviewQueryService.EventPage page = queryService.findPlans(reviewId, cursor, PLAN_PAGE_SIZE);
            plans.addAll(page.items());
            if (page.nextAfterSequence() == null) {
                return List.copyOf(plans);
            }
            cursor = page.nextAfterSequence();
        }
        throw new IllegalStateException("review plan history exceeds report pagination budget");
    }

    private List<EvidenceLinkView> findEvidenceLinks(
            ReviewId reviewId,
            List<ReviewQueryService.ClaimView> claims,
            List<ReviewQueryService.DebateView> debates,
            List<HumanReviewItem> humanItems) {
        Set<UUID> evidenceIds = new LinkedHashSet<>();
        claims.forEach(claim -> evidenceIds.addAll(claim.evidenceIds()));
        debates.forEach(debate -> {
            debate.claims().forEach(claim -> evidenceIds.addAll(claim.evidenceIds()));
            debate.turns().forEach(turn -> evidenceIds.addAll(turn.evidenceIds()));
        });
        humanItems.forEach(item -> item.evidenceIds().forEach(id -> evidenceIds.add(id.value())));
        return evidenceIds.stream()
                .sorted(Comparator.comparing(UUID::toString))
                .map(id -> new EvidenceLinkView(id, "/api/reviews/" + reviewId.value() + "/evidence/" + id))
                .toList();
    }

    private HumanReviewItemView toHumanItemView(HumanReviewItem item) {
        return new HumanReviewItemView(
                item.itemId(), item.type().name(), item.severity().name(), item.title(), item.content(),
                item.claimIds(), item.evidenceIds().stream().map(EvidenceId::value).toList(), item.action(),
                item.version(), item.status().name(), item.createdBy(), item.createdAt().toString(), item.updatedAt().toString());
    }

    private HumanGateView toGateView(HumanGateDecision decision) {
        return new HumanGateView(
                decision.gateVersion(), decision.result().name(), decision.reason(), decision.conditions(),
                decision.overrideReason(), decision.reviewerId(), decision.supersedesVersion(), decision.decidedAt().toString());
    }

    private String writeJson(ReportContent content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("unable to serialize review report", exception);
        }
    }

    private String toMarkdown(ReportContent content) {
        StringBuilder markdown = new StringBuilder("# 审核报告\n\n");
        markdown.append("- Review: ").append(content.summary().reviewId()).append('\n');
        markdown.append("- 阶段: ").append(content.summary().stage()).append('\n');
        markdown.append("- 最终 Gate: ").append(content.summary().gate() == null
                ? "未生成" : content.summary().gate().result()).append("\n\n");
        markdown.append("## 角色\n\n");
        content.roles().forEach(role -> markdown.append("- ").append(role.role())
                .append(" / ").append(role.agentLabel())
                .append(" / 初审完成: ").append(role.initialReviewCompleted()).append('\n'));
        markdown.append("\n## 计划\n\n");
        content.planEvents().forEach(plan -> markdown.append("- #").append(plan.sequence())
                .append(' ').append(plan.type()).append(" (" ).append(plan.occurredAt()).append(")\n"));
        markdown.append("\n## 公开论点\n\n");
        content.claims().forEach(claim -> {
            markdown.append("- ").append(claim.role())
                    .append(" [").append(claim.severity()).append(" · ").append(claim.position()).append("] ")
                    .append(claim.subjectKey()).append(": ").append(claim.statement());
            if (claim.reasonSummary() != null && !claim.reasonSummary().isBlank()) {
                markdown.append(" — ").append(claim.reasonSummary());
            }
            appendEvidenceReferences(markdown, claim.evidenceIds(), content.summary().reviewId());
            markdown.append('\n');
        });
        markdown.append("\n## 辩论与裁决\n\n");
        content.debates().forEach(debate -> {
            markdown.append("### ").append(debate.subjectKey()).append("\n\n");
            markdown.append("- 状态: ").append(debate.status()).append('\n');
            debate.claims().forEach(claim -> {
                markdown.append("- Claim [").append(claim.severity()).append("] ")
                        .append(claim.statement()).append(" — ").append(claim.reasonSummary());
                appendEvidenceReferences(markdown, claim.evidenceIds(), content.summary().reviewId());
                markdown.append('\n');
            });
            debate.turns().forEach(turn -> {
                markdown.append("- ").append(turn.actorRole()).append(" / ").append(turn.type())
                        .append("：").append(turn.content());
                appendEvidenceReferences(markdown, turn.evidenceIds(), content.summary().reviewId());
                markdown.append('\n');
            });
            if (debate.judgement() != null) {
                markdown.append("- 裁决: ").append(debate.judgement().result())
                        .append(" — ").append(debate.judgement().reasonSummary());

                markdown.append('\n');
            }
        });
        markdown.append("\n## 人工审核草稿\n\n");
        content.humanReviewItems().forEach(item -> {
            markdown.append("- [").append(item.severity()).append("] ").append(item.title())
                    .append("：").append(item.content());
            appendEvidenceReferences(markdown, item.evidenceIds(), content.summary().reviewId());
            markdown.append('\n');
        });
        markdown.append("\n## 最终决定版本\n\n");
        content.gateDecisions().forEach(decision -> {
            markdown.append("- v").append(decision.gateVersion())
                    .append(" / ").append(decision.result()).append("：").append(decision.reason());
            if (!decision.conditions().isEmpty()) {
                markdown.append("；条件: ").append(String.join("；", decision.conditions()));
            }
            markdown.append('\n');
        });
        markdown.append("\n## 证据回链\n\n");
        content.evidenceLinks().forEach(link -> markdown.append("- [").append(link.evidenceId())
                .append("](").append(link.href()).append(")\n"));
        return markdown.toString();
    }

    private void appendEvidenceReferences(StringBuilder markdown, List<UUID> evidenceIds, UUID reviewId) {
        if (evidenceIds.isEmpty()) {
            return;
        }
        markdown.append("；证据: ").append(evidenceIds.stream()
                .sorted(Comparator.comparing(UUID::toString))
                .map(evidenceId -> "[" + evidenceId + "](/api/reviews/" + reviewId + "/evidence/" + evidenceId + ")")
                .collect(Collectors.joining(", ")));
    }
    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte valueByte : digest) {
                result.append(String.format("%02x", valueByte));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** @author wangli */
    public record ReportContent(
            ReviewQueryService.ReviewSummary summary,
            List<ReviewQueryService.EventView> planEvents,
            List<RoleView> roles,
            List<ReviewQueryService.ClaimView> claims,
            List<ReviewQueryService.DebateView> debates,
            List<HumanReviewItemView> humanReviewItems,
            List<HumanGateView> gateDecisions,
            List<EvidenceLinkView> evidenceLinks) {
    }

    /** @author wangli */
    public record RoleView(String role, String agentLabel, boolean initialReviewCompleted) {
    }

    /** @author wangli */
    public record HumanReviewItemView(
            UUID itemId, String type, String severity, String title, String content, List<UUID> claimIds,
            List<UUID> evidenceIds, String action, long version, String status, String createdBy,
            String createdAt, String updatedAt) {
    }

    /** @author wangli */
    public record HumanGateView(
            long gateVersion, String result, String reason, List<String> conditions, String overrideReason,
            String reviewerId, Long supersedesVersion, String decidedAt) {
    }

    /** @author wangli */
    public record EvidenceLinkView(UUID evidenceId, String href) {
    }

    /** @author wangli */
    public record ReportVersionView(long reportVersion, long gateVersion, String contentHash, String createdAt) {
    }
}
