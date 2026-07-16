package ai.cc.chongming.review.infrastructure.agentscope.tool;

import ai.cc.chongming.review.application.EvidenceLedgerService;
import ai.cc.chongming.review.application.IntakeCancellation;
import ai.cc.chongming.review.application.EvidenceLedgerService.EvidenceSubmission;
import ai.cc.chongming.review.application.EvidenceLedgerService.EvidenceVerification;
import ai.cc.chongming.review.domain.model.EvidenceBlock;
import ai.cc.chongming.review.domain.model.ReviewTypes.EvidenceId;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Server-side facade that lets role agents submit only verifiable snapshot evidence.
 *
 * @author wangli
 */
@Component
public class EvidenceTools {

    private final EvidenceLedgerService evidenceLedgerService;

    public EvidenceTools(EvidenceLedgerService evidenceLedgerService) {
        this.evidenceLedgerService = Objects.requireNonNull(evidenceLedgerService, "evidenceLedgerService must not be null");
    }

    /**
     * Creates or deduplicates evidence by rereading the requested frozen source line.
     */
    public EvidenceBlock submitEvidence(
            RepositoryToolContext context,
            String relativePath,
            int lineNumber,
            IntakeCancellation cancellation) {
        RepositoryToolContext safeContext = Objects.requireNonNull(context, "context must not be null");
        return evidenceLedgerService.submit(
                safeContext.snapshot(), new EvidenceSubmission(relativePath, lineNumber), requireCancellation(cancellation));
    }

    /**
     * Batch-validates only evidence IDs that belong to the caller's review snapshot.
     */
    public Map<EvidenceId, EvidenceVerification> validateEvidence(
            RepositoryToolContext context, Set<EvidenceId> evidenceIds, IntakeCancellation cancellation) {
        RepositoryToolContext safeContext = Objects.requireNonNull(context, "context must not be null");
        return evidenceLedgerService.validateAll(safeContext.snapshot(), evidenceIds, requireCancellation(cancellation));
    }

    private IntakeCancellation requireCancellation(IntakeCancellation cancellation) {
        return Objects.requireNonNull(cancellation, "cancellation must not be null");
    }
}
