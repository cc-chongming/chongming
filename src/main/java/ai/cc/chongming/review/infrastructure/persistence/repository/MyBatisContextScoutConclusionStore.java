package ai.cc.chongming.review.infrastructure.persistence.repository;

import ai.cc.chongming.review.domain.model.ContextScoutConclusion;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.ContextScoutConclusionStore;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ContextScoutConclusionPersistenceMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * [AIREVIEW-PLAN-023#5] Durable MyBatis store for restart-safe Context Scout conclusions.
 *
 * @author zyj
 */
@Repository
@ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")
public class MyBatisContextScoutConclusionStore implements ContextScoutConclusionStore {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, List<String>>> ROLE_SCOPES_TYPE = new TypeReference<>() {
    };

    private final ContextScoutConclusionPersistenceMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisContextScoutConclusionStore(
            ContextScoutConclusionPersistenceMapper mapper, ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    @Transactional
    public void save(ContextScoutConclusion conclusion) {
        Objects.requireNonNull(conclusion, "conclusion must not be null");
        try {
            mapper.save(new ContextScoutConclusionPersistenceMapper.ContextScoutConclusionRow(
                    conclusion.reviewId().value().toString(),
                    conclusion.attemptNo(),
                    conclusion.schemaVersion(),
                    conclusion.summary(),
                    objectMapper.writeValueAsString(conclusion.moduleRoots()),
                    objectMapper.writeValueAsString(conclusion.entryPoints()),
                    objectMapper.writeValueAsString(conclusion.constraints()),
                    objectMapper.writeValueAsString(conclusion.risks()),
                    objectMapper.writeValueAsString(conclusion.evidencePaths()),
                    objectMapper.writeValueAsString(conclusion.roleScopes()),
                    conclusion.rawPublicResult(),
                    conclusion.createdAt().atOffset(ZoneOffset.UTC).toLocalDateTime()));
        } catch (Exception exception) {
            throw new IllegalStateException("Context Scout conclusion could not be persisted", exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContextScoutConclusion> find(ReviewId reviewId, int attemptNo) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        return Optional.ofNullable(mapper.find(reviewId.value().toString(), attemptNo)).map(this::toConclusion);
    }

    private ContextScoutConclusion toConclusion(
            ContextScoutConclusionPersistenceMapper.ContextScoutConclusionRow row) {
        try {
            return new ContextScoutConclusion(
                    new ReviewId(UUID.fromString(row.reviewId())),
                    row.attemptNo(),
                    row.schemaVersion(),
                    row.summaryText(),
                    objectMapper.readValue(row.moduleRootsJson(), STRING_LIST_TYPE),
                    objectMapper.readValue(row.entryPointsJson(), STRING_LIST_TYPE),
                    objectMapper.readValue(row.constraintsJson(), STRING_LIST_TYPE),
                    objectMapper.readValue(row.risksJson(), STRING_LIST_TYPE),
                    objectMapper.readValue(row.evidencePathsJson(), STRING_LIST_TYPE),
                    objectMapper.readValue(row.roleScopesJson(), ROLE_SCOPES_TYPE),
                    row.rawPublicResult(),
                    row.createdAt().toInstant(ZoneOffset.UTC));
        } catch (Exception exception) {
            throw new IllegalStateException("Context Scout conclusion could not be parsed", exception);
        }
    }
}
