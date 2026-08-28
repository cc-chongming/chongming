package ai.cc.chongming.review.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * [AIREVIEW-PLAN-022#5.2] MyBatis statements for the durable AG-UI runtime trace table.
 *
 * @author wangli
 */
@Mapper
public interface RuntimeTracePersistenceMapper {

    @Insert("""
            INSERT INTO runtime_trace_event
                (runtime_id, event_sequence, event_id, event_type, payload_json, review_id, attempt_no)
            VALUES (#{row.runtimeId}, #{row.sequence}, #{row.eventId}, #{row.eventType},
                    #{row.payloadJson}, #{row.reviewId}, #{row.attemptNo})
            ON DUPLICATE KEY UPDATE event_id = VALUES(event_id)
            """)
    int append(@Param("row") RuntimeTraceRow row);

    @Select("""
            SELECT runtime_id AS runtimeId, event_sequence AS sequence, event_id AS eventId,
                   event_type AS eventType, payload_json AS payloadJson, review_id AS reviewId,
                   attempt_no AS attemptNo, created_at AS createdAt
            FROM runtime_trace_event
            WHERE runtime_id = #{runtimeId} AND event_sequence > #{afterSequence}
            ORDER BY event_sequence ASC
            LIMIT #{limit}
            """)
    List<RuntimeTraceRow> findAfter(
            @Param("runtimeId") String runtimeId,
            @Param("afterSequence") long afterSequence,
            @Param("limit") int limit);

    @Select("SELECT COALESCE(MAX(event_sequence), 0) FROM runtime_trace_event WHERE runtime_id = #{runtimeId}")
    long maxSequence(@Param("runtimeId") String runtimeId);

    @Delete("""
            DELETE FROM runtime_trace_event WHERE runtime_id = #{runtimeId}
            AND event_sequence <= (SELECT m FROM (SELECT MAX(event_sequence) - #{keep} AS m
                FROM runtime_trace_event WHERE runtime_id = #{runtimeId}) t)
            """)
    int trim(@Param("runtimeId") String runtimeId, @Param("keep") int keep);

    /**
     * Full row for insert and reload. {@code runtimeId}/{@code reviewId}/{@code attemptNo} are
     * populated for inserts and also selected back on reads so the store can map to the domain row.
     * {@code createdAt} is DB-generated ({@code DEFAULT CURRENT_TIMESTAMP(3)}) and therefore null
     * for inserts, selected back on reads per [AIREVIEW-PLAN-068#2] for the SSE createdAt field.
     *
     * @author wangli
     */
    record RuntimeTraceRow(
            String runtimeId,
            long sequence,
            String eventId,
            String eventType,
            String payloadJson,
            String reviewId,
            int attemptNo,
            LocalDateTime createdAt) {
    }
}
