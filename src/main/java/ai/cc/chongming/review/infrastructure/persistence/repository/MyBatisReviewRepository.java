package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.exception.ReviewDomainException;
import ai.cc.chongming.review.domain.exception.ReviewErrorCode;
import ai.cc.chongming.review.domain.model.Claim;
import ai.cc.chongming.review.domain.model.Review;
import ai.cc.chongming.review.domain.repository.ReviewRepositories;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewPersistenceMapper;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import static ai.cc.chongming.review.domain.model.ReviewTypes.*;

/**
 * MyBatis implementation that restores aggregates and batch-loads cross-aggregate references.
 *
 * @author wangli
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")
public class MyBatisReviewRepository implements ReviewRepositories {

    private static final String INITIAL_REVIEW_COMPLETED = "INITIAL_REVIEW_COMPLETED";
    private static final String ACTIVATED = "ACTIVATED";

    private final ReviewPersistenceMapper mapper;

    public MyBatisReviewRepository(ReviewPersistenceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Review> findReview(ReviewId reviewId) {
        ReviewPersistenceMapper.ReviewRow row = mapper.findReview(reviewId.value().toString());
        if (row == null) {
            return Optional.empty();
        }

        List<RoleActivation> activations = mapper.findRoleActivations(row.reviewId()).stream()
                .map(role -> new RoleActivation(
                        RoleType.valueOf(role.roleCode()),
                        role.agentName(),
                        INITIAL_REVIEW_COMPLETED.equals(role.status())))
                .toList();
        Map<IdempotencyKey, String> commandResults = mapper.findCommandResults(row.reviewId()).stream()
                .collect(Collectors.toMap(
                        command -> new IdempotencyKey(command.idempotencyKey()),
                        ReviewPersistenceMapper.CommandResultRow::resultReference,
                        (left, right) -> left,
                        LinkedHashMap::new));

        return Optional.of(Review.restore(
                reviewId,
                ReviewStage.valueOf(row.stage()),
                row.attemptNo(),
                row.version(),
                activations,
                commandResults));
    }

    @Override
    @Transactional
    public void saveReview(Review review, long expectedVersion) {
        ReviewPersistenceMapper.ReviewRow row = new ReviewPersistenceMapper.ReviewRow(
                review.id().value().toString(), review.stage().name(), review.attemptNo(), review.version());
        if (mapper.updateReview(row, expectedVersion) != 1) {
            throw new ReviewDomainException(
                    ReviewErrorCode.VERSION_CONFLICT,
                    "review version no longer matches the persisted aggregate");
        }

        for (RoleActivation activation : review.roleActivations()) {
            mapper.insertRoleActivation(new ReviewPersistenceMapper.RoleActivationRow(
                    UUID.randomUUID().toString(),
                    review.id().value().toString(),
                    review.attemptNo(),
                    activation.roleType().name(),
                    activation.agentLabel(),
                    activation.initialReviewCompleted() ? INITIAL_REVIEW_COMPLETED : ACTIVATED));
        }
        for (Map.Entry<IdempotencyKey, String> commandResult : review.commandResults().entrySet()) {
            mapper.insertCommandResult(new ReviewPersistenceMapper.CommandResultRow(
                    review.id().value().toString(),
                    commandResult.getKey().value(),
                    commandResult.getValue()));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<ClaimId, Claim> findClaimsByIds(ReviewId reviewId, Set<ClaimId> claimIds) {
        if (claimIds.isEmpty()) {
            return Map.of();
        }
        Set<String> persistedIds = claimIds.stream().map(id -> id.value().toString()).collect(Collectors.toSet());
        Map<String, List<EvidenceReference>> evidenceByClaimId = mapper.findEvidenceByClaimIds(persistedIds).stream()
                .collect(Collectors.groupingBy(
                        ReviewPersistenceMapper.ClaimEvidenceRow::claimId,
                        LinkedHashMap::new,
                        Collectors.mapping(this::toEvidenceReference, Collectors.toList())));

        Map<ClaimId, Claim> claims = new LinkedHashMap<>();
        for (ReviewPersistenceMapper.ClaimRow row : mapper.findClaimsByIds(reviewId.value().toString(), persistedIds)) {
            ClaimId claimId = new ClaimId(UUID.fromString(row.claimId()));
            claims.put(claimId, new Claim(
                    claimId,
                    new ReviewId(UUID.fromString(row.reviewId())),
                    RoleType.valueOf(row.roleType()),
                    row.subjectKey(),
                    ClaimSeverity.valueOf(row.severity()),
                    ClaimPosition.valueOf(row.position()),
                    row.statement(),
                    row.reasonSummary(),
                    evidenceByClaimId.getOrDefault(row.claimId(), List.of()),
                    ClaimStatus.valueOf(row.status())));
        }
        return Map.copyOf(claims);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<EvidenceId, EvidenceReference> findEvidenceByIds(ReviewId reviewId, Set<EvidenceId> evidenceIds) {
        if (evidenceIds.isEmpty()) {
            return Map.of();
        }
        Set<String> persistedIds = evidenceIds.stream().map(id -> id.value().toString()).collect(Collectors.toSet());
        return mapper.findEvidenceByIds(reviewId.value().toString(), persistedIds).stream()
                .collect(Collectors.toUnmodifiableMap(
                        row -> new EvidenceId(UUID.fromString(row.evidenceId())),
                        this::toEvidenceReference));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<TurnId, DebateTurn> findTurnsByIds(ReviewId reviewId, Set<TurnId> turnIds) {
        if (turnIds.isEmpty()) {
            return Map.of();
        }
        Set<String> persistedIds = turnIds.stream().map(id -> id.value().toString()).collect(Collectors.toSet());
        Map<String, List<EvidenceId>> evidenceByTurnId = mapper.findEvidenceByTurnIds(persistedIds).stream()
                .collect(Collectors.groupingBy(
                        ReviewPersistenceMapper.TurnEvidenceRow::turnId,
                        LinkedHashMap::new,
                        Collectors.mapping(row -> new EvidenceId(UUID.fromString(row.evidenceId())), Collectors.toList())));

        Map<TurnId, DebateTurn> turns = new LinkedHashMap<>();
        for (ReviewPersistenceMapper.DebateTurnRow row : mapper.findTurnsByIds(reviewId.value().toString(), persistedIds)) {
            TurnId turnId = new TurnId(UUID.fromString(row.turnId()));
            turns.put(turnId, new DebateTurn(
                    turnId,
                    new TopicId(UUID.fromString(row.topicId())),
                    row.round(),
                    RoleType.valueOf(row.actorRole()),
                    row.targetRole() == null ? null : RoleType.valueOf(row.targetRole()),
                    DebateTurnType.valueOf(row.turnType()),
                    row.targetClaimId() == null ? null : new ClaimId(UUID.fromString(row.targetClaimId())),
                    row.targetTurnId() == null ? null : new TurnId(UUID.fromString(row.targetTurnId())),
                    row.publicContent(),
                    evidenceByTurnId.getOrDefault(row.turnId(), List.of()),
                    row.stanceBefore() == null ? null : ClaimPosition.valueOf(row.stanceBefore()),
                    row.stanceAfter() == null ? null : ClaimPosition.valueOf(row.stanceAfter()),
                    row.createdAt().toInstant(ZoneOffset.UTC)));
        }
        return Map.copyOf(turns);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findCommandResult(ReviewId reviewId, IdempotencyKey idempotencyKey) {
        return Optional.ofNullable(mapper.findCommandResult(reviewId.value().toString(), idempotencyKey.value()));
    }

    private EvidenceReference toEvidenceReference(ReviewPersistenceMapper.ClaimEvidenceRow row) {
        return new EvidenceReference(
                new EvidenceId(UUID.fromString(row.evidenceId())),
                row.snapshotId(),
                row.relativePath(),
                row.lineNumber(),
                row.snippetHash());
    }

    private EvidenceReference toEvidenceReference(ReviewPersistenceMapper.EvidenceRow row) {
        return new EvidenceReference(
                new EvidenceId(UUID.fromString(row.evidenceId())),
                row.snapshotId(),
                row.relativePath(),
                row.lineNumber(),
                row.snippetHash());
    }
}
