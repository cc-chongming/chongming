package ai.cc.chongming.review.domain.security;

import java.util.Objects;
import java.util.Set;

/**
 * [AIREVIEW-PLAN-011#1.2][AIREVIEW-PLAN-011#1.7] Replaceable source of the authenticated human reviewer identity.
 *
 * @author wangli
 */
@FunctionalInterface
public interface ReviewerIdentityProvider {

    ReviewerIdentity currentReviewer();

    /**
     * @author wangli
     */
    record ReviewerIdentity(String reviewerId, Set<Permission> permissions) {

        public ReviewerIdentity {
            if (reviewerId == null || reviewerId.isBlank()) {
                throw new IllegalArgumentException("reviewerId must not be blank");
            }
            permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions must not be null"));
        }

        public boolean canReview() {
            return permissions.contains(Permission.REVIEW);
        }
    }

    /**
     * @author wangli
     */
    enum Permission {
        REVIEW,
        OVERRIDE
    }
}
