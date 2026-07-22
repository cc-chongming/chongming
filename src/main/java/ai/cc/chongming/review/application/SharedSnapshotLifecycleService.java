package ai.cc.chongming.review.application;

import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Periodically removes only expired, unreferenced shared repository snapshots.
 *
 * @author wangli
 */
@Service
public class SharedSnapshotLifecycleService {

    private final RepositorySnapshotService snapshotService;
    private final Duration retention;

    public SharedSnapshotLifecycleService(
            RepositorySnapshotService snapshotService,
            @Value("${review.repository-snapshot.retention:PT720H}") Duration retention) {
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService must not be null");
        this.retention = Objects.requireNonNull(retention, "retention must not be null");
    }

    @Scheduled(fixedDelayString = "${review.repository-snapshot.cleanup-interval:PT1H}")
    public void cleanupExpiredSnapshots() {
        snapshotService.cleanupUnreferencedSnapshots(retention);
    }
}
