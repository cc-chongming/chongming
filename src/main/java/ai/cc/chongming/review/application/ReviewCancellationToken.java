package ai.cc.chongming.review.application;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mutable review-attempt cancellation signal that also observes an upstream request signal.
 *
 * @author wangli
 */
public final class ReviewCancellationToken implements IntakeCancellation {

    private final IntakeCancellation upstream;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public ReviewCancellationToken(IntakeCancellation upstream) {
        this.upstream = upstream == null ? IntakeCancellation.neverCancelled() : upstream;
    }

    /**
     * Requests cooperative cancellation for the active review attempt.
     */
    public void cancel() {
        cancelled.set(true);
    }

    /**
     * Clears a local cancellation request when an existing persisted runtime is resumed.
     */
    public void resume() {
        cancelled.set(false);
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get() || upstream.isCancelled();
    }
}
