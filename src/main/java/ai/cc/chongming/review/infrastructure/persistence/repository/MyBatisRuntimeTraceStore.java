package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.RuntimeTraceStore;
import ai.cc.chongming.review.infrastructure.persistence.mapper.RuntimeTracePersistenceMapper;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * [AIREVIEW-PLAN-022#5.3] Durable runtime trace store used whenever review persistence is enabled.
 *
 * @author wangli
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")
public class MyBatisRuntimeTraceStore implements RuntimeTraceStore {

    // runtime_trace_event.created_at is DB-generated (DEFAULT CURRENT_TIMESTAMP(3)) in the MySQL
    // server's local (China) wall clock, unlike Java-written columns that follow the UTC wall-clock
    // convention; reading it back as UTC shifted timestamps by +8h (LRN-20260820-001).
    private static final ZoneId DB_SERVER_ZONE = ZoneId.of("Asia/Shanghai");

    private final RuntimeTracePersistenceMapper mapper;

    public MyBatisRuntimeTraceStore(RuntimeTracePersistenceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    @Transactional
    public void append(
            String runtimeId,
            long sequence,
            String eventId,
            String eventType,
            String payloadJson,
            ReviewId reviewId,
            int attemptNo) {
        Objects.requireNonNull(runtimeId, "runtimeId must not be null");
        Objects.requireNonNull(payloadJson, "payloadJson must not be null");
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (mapper.append(new RuntimeTracePersistenceMapper.RuntimeTraceRow(
                runtimeId, sequence, eventId, eventType, payloadJson, reviewId.value().toString(),
                attemptNo, null)) != 1) {
            throw new IllegalStateException("runtime trace event was not persisted");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuntimeTraceRow> findAfter(String runtimeId, long afterSequence, int limit) {
        return mapper.findAfter(runtimeId, afterSequence, limit).stream()
                .map(row -> new RuntimeTraceRow(
                        row.sequence(),
                        row.eventId(),
                        row.eventType(),
                        row.payloadJson(),
                        row.createdAt().atZone(DB_SERVER_ZONE).toInstant()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long maxSequence(String runtimeId) {
        return mapper.maxSequence(runtimeId);
    }

    @Override
    @Transactional
    public void trim(String runtimeId, int keep) {
        mapper.trim(runtimeId, keep);
    }
}
