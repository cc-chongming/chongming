package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.model.ReviewReport;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.ReviewReportStore;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewReportMapper;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * [AIREVIEW-PLAN-021#8][REQLIFE-M1] Durable report store used whenever review persistence is enabled.
 *
 * @author zyj
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")
public class MyBatisReviewReportStore implements ReviewReportStore {

    private final ReviewReportMapper mapper;

    public MyBatisReviewReportStore(ReviewReportMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    @Transactional
    public void append(ReviewReport report) {
        ReviewReport nonNullReport = Objects.requireNonNull(report, "report must not be null");
        Integer attemptNo = mapper.findCurrentAttempt(nonNullReport.reviewId().value().toString());
        if (attemptNo == null || attemptNo < 1) {
            throw new IllegalStateException("review must exist before its report is persisted");
        }
        if (mapper.insert(new ReviewReportMapper.ReportRow(
                nonNullReport.reportId().toString(),
                nonNullReport.reviewId().value().toString(),
                attemptNo,
                nonNullReport.reportVersion(),
                nonNullReport.gateVersion(),
                nonNullReport.contentHash(),
                nonNullReport.contentJson(),
                nonNullReport.markdown(),
                nonNullReport.createdAt().atOffset(ZoneOffset.UTC).toLocalDateTime())) != 1) {
            throw new IllegalStateException("review report was not persisted");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewReport> findLatest(ReviewId reviewId) {
        return Optional.ofNullable(mapper.findLatest(reviewId.value().toString())).map(this::toReport);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewReport> findVersion(ReviewId reviewId, long reportVersion) {
        return reportVersion < 1
                ? Optional.empty()
                : Optional.ofNullable(mapper.findVersion(reviewId.value().toString(), reportVersion)).map(this::toReport);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewReport> findVersions(ReviewId reviewId) {
        return mapper.findVersions(reviewId.value().toString()).stream().map(this::toReport).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewReport> findLatestAcrossReviews(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("report limit must be positive");
        }
        return mapper.findLatestAcrossReviews(limit).stream().map(this::toReport).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ReportMetadataPage findLatestMetadataPage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be positive and size must be between 1 and 100");
        }
        long offset = ((long) page - 1L) * size;
        List<ReportMetadata> items = mapper.findLatestMetadataPage(offset, size).stream()
                .map(row -> new ReportMetadata(
                        new ReviewId(UUID.fromString(row.reviewId())),
                        row.reportVersion(),
                        row.gateVersion() == null ? 1L : row.gateVersion(),
                        row.contentHash(),
                        row.createdAt().toInstant(ZoneOffset.UTC)))
                .toList();
        return new ReportMetadataPage(items, page, size, mapper.countLatestMetadata());
    }

    private ReviewReport toReport(ReviewReportMapper.ReportRow row) {
        return new ReviewReport(
                UUID.fromString(row.reportId()),
                new ReviewId(UUID.fromString(row.reviewId())),
                row.reportVersion(),
                row.gateVersion() == null ? 1L : row.gateVersion(),
                row.contentHash(),
                row.contentJson() == null ? "{}" : row.contentJson(),
                row.markdown() == null ? row.contentJson() : row.markdown(),
                row.createdAt().toInstant(ZoneOffset.UTC));
    }
}
