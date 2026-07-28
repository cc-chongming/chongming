package ai.cc.chongming.review.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Verifies the attempt-local repository read budget cannot be exceeded.
 *
 * @author wangli
 */
class RepositoryReadBudgetTests {

    @Test
    void consumesOnlyTheConfiguredNumberOfCalls() {
        RepositoryReadBudget budget = new RepositoryReadBudget(2);

        assertThat(budget.tryConsume()).isTrue();
        assertThat(budget.tryConsume()).isTrue();
        assertThat(budget.tryConsume()).isFalse();
    }
}
