package ai.cc.chongming.review.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests the review workbench entry-page mapping.
 *
 * @author wangli
 */
class ReviewWorkbenchControllerTests {

    private final ReviewWorkbenchController controller = new ReviewWorkbenchController();

    @Test
    void resolvesWorkbenchDirectoryToStaticIndex() {
        assertThat(controller.workbench()).isEqualTo("forward:/review/index.html");
    }
}
