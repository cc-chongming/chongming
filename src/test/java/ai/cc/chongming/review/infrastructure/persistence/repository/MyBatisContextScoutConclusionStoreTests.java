package ai.cc.chongming.review.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.cc.chongming.review.domain.model.ContextScoutConclusion;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ContextScoutConclusionPersistenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * [AIREVIEW-PLAN-023#5] Verifies MyBatis JSON round-tripping for persisted Scout conclusions.
 *
 * @author zyj
 */
class MyBatisContextScoutConclusionStoreTests {

    @Test
    void savesAndReloadsEveryPublicConclusionField() {
        ContextScoutConclusionPersistenceMapper mapper = mock(ContextScoutConclusionPersistenceMapper.class);
        MyBatisContextScoutConclusionStore store = new MyBatisContextScoutConclusionStore(mapper, new ObjectMapper());
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        ContextScoutConclusion conclusion = new ContextScoutConclusion(
                reviewId,
                3,
                1,
                "入口与边界已收集",
                List.of("src/main", "frontend"),
                List.of("ChongmingApplication", "ReviewLiveView.vue"),
                List.of("MySQL 5.6"),
                List.of("历史数据无结构化结论"),
                List.of("pom.xml", "frontend/src/views/ReviewLiveView.vue"),
                Map.of("BACKEND", List.of("src/main/"), "FRONTEND", List.of("frontend/")),
                "{\"summary\":\"入口与边界已收集\"}",
                Instant.parse("2026-08-10T08:00:00Z"));
        ArgumentCaptor<ContextScoutConclusionPersistenceMapper.ContextScoutConclusionRow> captor =
                ArgumentCaptor.forClass(ContextScoutConclusionPersistenceMapper.ContextScoutConclusionRow.class);
        when(mapper.save(captor.capture())).thenReturn(1);
        when(mapper.find(reviewId.value().toString(), 3)).thenAnswer(ignored -> captor.getValue());

        store.save(conclusion);
        ContextScoutConclusion reloaded = store.find(reviewId, 3).orElseThrow();

        assertThat(reloaded).isEqualTo(conclusion);
        assertThat(captor.getValue().moduleRootsJson()).isEqualTo("[\"src/main\",\"frontend\"]");
        assertThat(captor.getValue().roleScopesJson()).contains("BACKEND", "FRONTEND");
    }
}
