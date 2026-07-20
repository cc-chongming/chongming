package ai.cc.chongming.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cc.chongming.review.config.ReviewProperties;
import ai.cc.chongming.review.domain.repository.ReviewRegistry;
import ai.cc.chongming.review.infrastructure.document.MarkdownRequirementParser;
import ai.cc.chongming.review.infrastructure.document.MarkdownRequirementValidator;
import ai.cc.chongming.review.infrastructure.document.RequirementSnapshotStore;
import ai.cc.chongming.review.infrastructure.persistence.mapper.ReviewPersistenceMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Tests immutable workspace snapshots and deterministic duplicate intake handling.
 *
 * @author wangli
 */
class ReviewIntakeServiceTests {

    @TempDir
    Path workspaceRoot;

    @Test
    void reusesMatchingIntakeAndCreatesNewAttemptOnlyWhenRequested() throws Exception {
        ReviewIntakeService service = newService();
        ReviewIntakeRequest request = request(false);

        ReviewIntakeResult created = service.intake(request);
        ReviewIntakeResult replayed = service.intake(request);
        ReviewIntakeResult newAttempt = service.intake(request(true));

        assertThat(created.reused()).isFalse();
        assertThat(replayed.reused()).isTrue();
        assertThat(replayed.snapshot().reviewId()).isEqualTo(created.snapshot().reviewId());
        assertThat(replayed.snapshot().attemptNo()).isEqualTo(1);
        assertThat(newAttempt.reused()).isFalse();
        assertThat(newAttempt.snapshot().reviewId()).isEqualTo(created.snapshot().reviewId());
        assertThat(newAttempt.snapshot().attemptNo()).isEqualTo(2);
        assertThat(Files.readString(created.workspaceSnapshot().rawMarkdownPath()))
                .isEqualTo("# Requirement\r\nImplement the intake endpoint.");
        assertThat(Files.readString(created.workspaceSnapshot().normalizedMarkdownPath()))
                .isEqualTo("# Requirement\nImplement the intake endpoint.");
        assertThat(Files.readString(created.workspaceSnapshot().manifestPath()))
                .contains("\"snapshotId\"")
                .contains(created.snapshot().contentHash())
                .contains("\"parserVersion\" : \"markdown-line-parser-v1\"");
    }

    @Test
    void returnsOneImmutableSnapshotForConcurrentDuplicateSubmissions() throws Exception {
        ReviewIntakeService service = newService();
        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReviewIntakeResult>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 6; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return service.intake(request(false));
                }));
            }
            start.countDown();
            List<ReviewIntakeResult> results = new ArrayList<>();
            for (Future<ReviewIntakeResult> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }

            assertThat(results).extracting(result -> result.snapshot().snapshotId()).containsOnly(
                    results.getFirst().snapshot().snapshotId());
            assertThat(results).filteredOn(ReviewIntakeResult::reused).hasSize(5);
            assertThat(Files.walk(workspaceRoot)
                            .filter(path -> path.getFileName().toString().equals("snapshot-manifest.json"))
                            .toList())
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void doesNotPublishAWorkspaceSnapshotWhenCancellationIsRequested() {
        ReviewIntakeService service = newService();
        ReviewIntakeRequest cancelled = new ReviewIntakeRequest(
                request(false).requirementFile(),
                "D:/repositories/chongming",
                "main",
                "abc123",
                "user-001",
                false,
                () -> true);

        assertThatThrownBy(() -> service.intake(cancelled))
                .isInstanceOf(ReviewIntakeException.class)
                .extracting(exception -> ((ReviewIntakeException) exception).code())
                .isEqualTo("INTAKE_CANCELLED");
        assertThat(workspaceRoot.resolve("reviews")).doesNotExist();
    }

    @Test
    void persistsReviewRootWhenPersistenceIsEnabled() {
        ReviewPersistenceMapper mapper = mock(ReviewPersistenceMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ReviewPersistenceMapper> mapperProvider = mock(ObjectProvider.class);
        when(mapperProvider.getIfAvailable()).thenReturn(mapper);
        when(mapper.insertReviewRequest(any())).thenReturn(1);
        MarkdownRequirementValidator validator = new MarkdownRequirementValidator();
        MarkdownRequirementParser parser = new MarkdownRequirementParser();
        RequirementSnapshotStore store = new RequirementSnapshotStore(
                new ReviewProperties(workspaceRoot.toString(), 8, 2));
        ReviewIntakeService service = new ReviewIntakeService(
                validator, parser, store, ReviewRegistry.noop(), mapperProvider, true);

        service.intake(request(false));

        verify(mapper).insertReviewRequest(any(ReviewPersistenceMapper.ReviewRequestRow.class));
    }

    private ReviewIntakeService newService() {
        MarkdownRequirementValidator validator = new MarkdownRequirementValidator();
        MarkdownRequirementParser parser = new MarkdownRequirementParser();
        RequirementSnapshotStore store = new RequirementSnapshotStore(
                new ReviewProperties(workspaceRoot.toString(), 8, 2));
        return new ReviewIntakeService(validator, parser, store);
    }

    private ReviewIntakeRequest request(boolean forceNewAttempt) {
        MockMultipartFile file = new MockMultipartFile(
                "requirementFile",
                "requirements.md",
                "text/markdown",
                "# Requirement\r\nImplement the intake endpoint.".getBytes(StandardCharsets.UTF_8));
        return new ReviewIntakeRequest(
                file,
                "D:/repositories/chongming",
                "main",
                "abc123",
                "user-001",
                forceNewAttempt);
    }
}
