package ai.cc.chongming.review.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.review.domain.model.ReviewTypes.ReviewId;
import ai.cc.chongming.review.domain.model.ReviewTypes.RoleType;
import ai.cc.chongming.review.infrastructure.agentscope.tool.RepositoryFileGrant;
import ai.cc.chongming.review.infrastructure.agentscope.tool.RepositoryFileGrantSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-024] Tests unguessable fileRef grants and O(1) grant-set resolution.
 *
 * @author wangli
 */
class RepositoryFileGrantTests {

    private final ReviewId reviewId = new ReviewId(UUID.randomUUID());

    @Test
    void issuesUnguessableUniqueFileRefsBoundToTheServerSideIdentity() {
        RepositoryFileGrant first = RepositoryFileGrant.issue(
                reviewId, 1, RoleType.BACKEND, "c".repeat(40), "src/main/java/App.java");
        RepositoryFileGrant second = RepositoryFileGrant.issue(
                reviewId, 1, RoleType.BACKEND, "c".repeat(40), "src/main/java/App.java");

        assertThat(first.fileRef()).isNotEqualTo(second.fileRef());
        assertThat(first.reviewId()).isEqualTo(reviewId);
        assertThat(first.attemptNo()).isEqualTo(1);
        assertThat(first.roleType()).isEqualTo(RoleType.BACKEND);
        assertThat(first.snapshotCommit()).isEqualTo("c".repeat(40));
        assertThat(first.normalizedPath()).isEqualTo("src/main/java/App.java");
        assertThat(first.fileRef()).matches("[A-Za-z0-9_-]{16,64}");
    }

    @Test
    void rejectsGuessableOrMalformedFileRefsAndPaths() {
        assertThatThrownBy(() -> new RepositoryFileGrant(
                reviewId, 1, RoleType.BACKEND, "c".repeat(40), "src/App.java", "src/App.java"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fileRef");
        assertThatThrownBy(() -> RepositoryFileGrant.issue(
                reviewId, 1, RoleType.BACKEND, "c".repeat(40), "../outside.java"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalizedPath");
        assertThatThrownBy(() -> RepositoryFileGrant.issue(
                reviewId, 1, RoleType.BACKEND, "c".repeat(40), "/absolute/path.java"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepositoryFileGrant.issue(
                reviewId, 0, RoleType.BACKEND, "c".repeat(40), "src/App.java"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attemptNo");
    }

    @Test
    void normalizesBackslashPathsServerSide() {
        RepositoryFileGrant grant = RepositoryFileGrant.issue(
                reviewId, 1, RoleType.BACKEND, "c".repeat(40), "src\\main\\java\\App.java");

        assertThat(grant.normalizedPath()).isEqualTo("src/main/java/App.java");
    }

    @Test
    void resolvesFileRefsInConstantTimeAndFiltersGrantsByRole() {
        RepositoryFileGrant backend = RepositoryFileGrant.issue(
                reviewId, 1, RoleType.BACKEND, "c".repeat(40), "src/main/java/App.java");
        RepositoryFileGrant frontend = RepositoryFileGrant.issue(
                reviewId, 1, RoleType.FRONTEND, "c".repeat(40), "frontend/app.js");
        RepositoryFileGrantSet grantSet = RepositoryFileGrantSet.of(List.of(backend, frontend));

        assertThat(grantSet.resolve(backend.fileRef())).contains(backend);
        assertThat(grantSet.resolve(frontend.fileRef())).contains(frontend);
        assertThat(grantSet.resolve("unknown-file-ref-token")).isEmpty();
        assertThat(grantSet.resolve(null)).isEmpty();
        assertThat(grantSet.fileRefFor("src/main/java/App.java")).contains(backend.fileRef());
        assertThat(grantSet.containsPath("frontend/app.js")).isTrue();
        assertThat(grantSet.containsPath("docs/readme.md")).isFalse();
        assertThat(grantSet.size()).isEqualTo(2);
        assertThat(grantSet.isEmpty()).isFalse();
        assertThat(grantSet.paths()).containsExactlyInAnyOrder("src/main/java/App.java", "frontend/app.js");

        RepositoryFileGrantSet backendOnly = grantSet.forRole(RoleType.BACKEND);
        assertThat(backendOnly.resolve(backend.fileRef())).contains(backend);
        assertThat(backendOnly.resolve(frontend.fileRef())).isEmpty();
        assertThat(grantSet.forRole(RoleType.PRODUCT).isEmpty()).isTrue();
    }

    @Test
    void keepsOnlyGrantsBoundToTheSameReviewAttemptAndRole() {
        RepositoryFileGrant current = RepositoryFileGrant.issue(
                reviewId, 1, RoleType.BACKEND, "c".repeat(40), "src/main/java/App.java");
        RepositoryFileGrant otherAttempt = RepositoryFileGrant.issue(
                reviewId, 2, RoleType.BACKEND, "c".repeat(40), "src/main/java/App.java");
        RepositoryFileGrant otherReview = RepositoryFileGrant.issue(
                new ReviewId(UUID.randomUUID()), 1, RoleType.BACKEND, "c".repeat(40), "src/main/java/App.java");
        RepositoryFileGrantSet bound = RepositoryFileGrantSet.of(List.of(current, otherAttempt, otherReview))
                .boundTo(reviewId, 1, RoleType.BACKEND);

        assertThat(bound.size()).isEqualTo(1);
        assertThat(bound.resolve(current.fileRef())).contains(current);
        assertThat(bound.resolve(otherAttempt.fileRef())).isEmpty();
        assertThat(bound.resolve(otherReview.fileRef())).isEmpty();
    }

    @Test
    void issuesDistinctTokensAcrossALargeGrantBatch() {
        Set<String> fileRefs = new HashSet<>();
        for (int index = 0; index < 500; index++) {
            fileRefs.add(RepositoryFileGrant.randomFileRef());
        }
        assertThat(fileRefs).hasSize(500);
        assertThat(RepositoryFileGrantSet.empty().isEmpty()).isTrue();
        assertThat(RepositoryFileGrantSet.of(List.of()).isEmpty()).isTrue();
    }

    @Test
    void rejectsDuplicateFileRefsWithinOneGrantSet() {
        RepositoryFileGrant grant = RepositoryFileGrant.issue(
                reviewId, 1, RoleType.BACKEND, "c".repeat(40), "src/main/java/App.java");

        assertThatThrownBy(() -> RepositoryFileGrantSet.of(List.of(grant, grant)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
    }
}
