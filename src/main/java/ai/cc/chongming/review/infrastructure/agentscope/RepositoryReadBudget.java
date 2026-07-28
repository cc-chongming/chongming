package ai.cc.chongming.review.infrastructure.agentscope;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Attempt-local cap for repository tool calls made by an initial-review role.
 *
 * @author wangli
 */
public final class RepositoryReadBudget {

    private final AtomicInteger remaining;

    public RepositoryReadBudget(int maximumCalls) {
        if (maximumCalls < 1) {
            throw new IllegalArgumentException("maximumCalls must be positive");
        }
        this.remaining = new AtomicInteger(maximumCalls);
    }

    public boolean tryConsume() {
        return remaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0;
    }
}
