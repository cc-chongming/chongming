package ai.cc.chongming.review.application;

import org.springframework.http.HttpStatus;

/**
 * Cooperative cancellation contract for streamed Markdown intake work.
 *
 * @author wangli
 */
@FunctionalInterface
public interface IntakeCancellation {

    /**
     * Indicates whether the current intake operation should stop.
     *
     * @return {@code true} when cancellation was requested
     */
    boolean isCancelled();

    /**
     * Stops the current operation with a stable client-visible cancellation error when requested.
     */
    default void checkCancelled() {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new ReviewIntakeException("INTAKE_CANCELLED", HttpStatus.CONFLICT, "Review intake was cancelled");
        }
    }

    /**
     * Creates a cancellation token that never cancels work.
     *
     * @return non-cancelling token
     */
    static IntakeCancellation neverCancelled() {
        return () -> false;
    }
}
