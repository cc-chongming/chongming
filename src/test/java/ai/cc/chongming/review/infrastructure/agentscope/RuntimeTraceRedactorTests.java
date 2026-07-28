package ai.cc.chongming.review.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-017#4.5] Regression coverage for browser-visible runtime trace redaction.
 *
 * @author wangli
 */
class RuntimeTraceRedactorTests {

    private final RuntimeTraceRedactor redactor = new RuntimeTraceRedactor();

    @Test
    void redactsCredentialsAndHostPathsBeforePublishingModelText() {
        String result = redactor.redactVisibleText(
                "token=secret-value path=E:\\aicode\\chongming\\application.yml bearer abc.def");

        assertThat(result)
                .doesNotContain("secret-value")
                .doesNotContain("E:\\aicode")
                .doesNotContain("abc.def")
                .contains("[REDACTED]")
                .contains("[HOST_PATH_REDACTED]");
    }
}
