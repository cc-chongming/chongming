package ai.cc.chongming.review.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.model.Requirement;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementId;
import ai.cc.chongming.review.domain.model.RequirementTypes.RequirementStatus;
import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.repository.RequirementRepository.RequirementFilter;
import ai.cc.chongming.review.infrastructure.persistence.mapper.RequirementMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * [AIREVIEW-PLAN-021#2] Covers the durable aggregate conversion and optimistic-write boundary without MySQL.
 *
 * @author zyj
 */
class MyBatisRequirementRepositoryTests {

    @Test
    void insertsNewDraftWithItsInitialVersion() {
        RequirementMapper mapper = mock(RequirementMapper.class);
        when(mapper.insert(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        Requirement requirement = Requirement.draft(
                new RequirementId(UUID.randomUUID()), "统一身份", "同步身份", "alice", "bob", "cx-ai", "P1");

        new MyBatisRequirementRepository(mapper).save(requirement);

        ArgumentCaptor<RequirementMapper.RequirementRow> row = ArgumentCaptor.forClass(RequirementMapper.RequirementRow.class);
        verify(mapper).insert(row.capture());
        assertThat(row.getValue())
                .extracting(RequirementMapper.RequirementRow::id, RequirementMapper.RequirementRow::status,
                        RequirementMapper.RequirementRow::reviewId, RequirementMapper.RequirementRow::version)
                .containsExactly(requirement.id().value().toString(), "DRAFT", null, 0L);
    }

    @Test
    void updatesRevisedRequirementUsingThePreviousVersionAndRejectsLostWrites() {
        RequirementMapper mapper = mock(RequirementMapper.class);
        Requirement requirement = Requirement.restore(
                new RequirementId(UUID.randomUUID()), "旧标题", "旧描述", "alice", null, "cx-ai", "P1",
                RequirementStatus.DRAFT, null, java.time.Instant.EPOCH, java.time.Instant.EPOCH, 4L);
        requirement.revise("新标题", "新描述", "bob", "cx-ai", "P0", 4L);
        when(mapper.update(org.mockito.ArgumentMatchers.any(), eq(4L))).thenReturn(1);

        new MyBatisRequirementRepository(mapper).save(requirement);

        verify(mapper).update(org.mockito.ArgumentMatchers.any(), eq(4L));
        when(mapper.update(org.mockito.ArgumentMatchers.any(), eq(4L))).thenReturn(0);
        assertThatThrownBy(() -> new MyBatisRequirementRepository(mapper).save(requirement))
                .isInstanceOf(RequirementDomainException.class);
    }

    @Test
    void restoresRowsForIdAndReviewLookups() {
        RequirementMapper mapper = mock(RequirementMapper.class);
        ReviewId reviewId = new ReviewId(UUID.randomUUID());
        RequirementMapper.RequirementRow row = row(RequirementStatus.PENDING_REVIEW, reviewId.value().toString(), 7L);
        when(mapper.findById(row.id())).thenReturn(row);
        when(mapper.findByReviewId(reviewId.value().toString())).thenReturn(row);
        MyBatisRequirementRepository repository = new MyBatisRequirementRepository(mapper);

        Requirement byId = repository.findById(new RequirementId(UUID.fromString(row.id()))).orElseThrow();
        Requirement byReview = repository.findByReviewId(reviewId).orElseThrow();

        assertThat(byId)
                .extracting(Requirement::title, Requirement::status, Requirement::reviewId, Requirement::version)
                .containsExactly("统一身份", RequirementStatus.PENDING_REVIEW, reviewId, 7L);
        assertThat(byReview.id()).isEqualTo(byId.id());
    }

    @Test
    void normalizesPageFiltersAndReturnsPersistentStatusCounts() {
        RequirementMapper mapper = mock(RequirementMapper.class);
        RequirementMapper.RequirementRow row = row(RequirementStatus.DRAFT, null, 0L);
        when(mapper.countPage("DRAFT", "bob", "身份")).thenReturn(2L);
        when(mapper.findPage("DRAFT", "bob", "身份", 10L, 10)).thenReturn(List.of(row));
        when(mapper.countByStatus()).thenReturn(List.of(
                new RequirementMapper.StatusCountRow("DRAFT", 3L),
                new RequirementMapper.StatusCountRow("DONE", 2L)));
        MyBatisRequirementRepository repository = new MyBatisRequirementRepository(mapper);

        var page = repository.findPage(new RequirementFilter(RequirementStatus.DRAFT, " bob ", " 身份 "), 2, 10);

        assertThat(page).extracting(
                ai.cc.chongming.review.domain.repository.RequirementRepository.RequirementPage::total,
                value -> value.items().size()).containsExactly(2L, 1);
        assertThat(repository.countByStatus()).containsEntry(RequirementStatus.DRAFT, 3L).containsEntry(RequirementStatus.DONE, 2L);
        verify(mapper).countPage("DRAFT", "bob", "身份");
        verify(mapper).findPage("DRAFT", "bob", "身份", 10L, 10);
    }

    @Test
    void rejectsInvalidPageArgumentsBeforeQueryingTheMapper() {
        MyBatisRequirementRepository repository = new MyBatisRequirementRepository(mock(RequirementMapper.class));

        assertThatIllegalArgumentException().isThrownBy(() -> repository.findPage(null, 0, 20));
        assertThatIllegalArgumentException().isThrownBy(() -> repository.findPage(null, 1, 101));
    }

    private RequirementMapper.RequirementRow row(RequirementStatus status, String reviewId, long version) {
        LocalDateTime timestamp = LocalDateTime.of(2026, 8, 1, 8, 0);
        return new RequirementMapper.RequirementRow(
                UUID.randomUUID().toString(), "统一身份", "同步身份", status.name(), "alice", "bob", "cx-ai", "P1",
                reviewId, version, timestamp, timestamp.plusMinutes(1));
    }
}
