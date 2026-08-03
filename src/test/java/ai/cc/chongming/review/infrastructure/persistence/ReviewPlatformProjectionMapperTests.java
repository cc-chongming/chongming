package ai.cc.chongming.review.infrastructure.persistence;

import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewPlatformProjectionMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [AIREVIEW-PLAN-021#8][REQLIFE-H4] Ensures the platform list query never transfers report bodies.
 *
 * @author zyj
 */
class ReviewPlatformProjectionMapperTests {

    @Test
    void projectsOnlyReportMetadataForReviewLists() throws NoSuchMethodException {
        Select query = ReviewPlatformProjectionMapper.class
                .getMethod("findReviewPage", String.class, Boolean.class, Boolean.class, long.class, int.class)
                .getAnnotation(Select.class);

        assertThat(query.value()).allSatisfy(sql -> {
            assertThat(sql).doesNotContain("report.report_content");
            assertThat(sql).doesNotContain("report.markdown_content");
            assertThat(sql).doesNotContain("reportInner.report_content");
            assertThat(sql).doesNotContain("reportInner.markdown_content");
        });
    }
}
