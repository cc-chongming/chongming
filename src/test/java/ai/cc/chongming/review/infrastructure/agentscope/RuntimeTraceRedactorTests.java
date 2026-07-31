package ai.cc.chongming.review.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
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
                "token=secret-value path=E:\\aicode\\chongming\\application.yml file=/srv/chongming/application.yml bearer abc.def");

        assertThat(result)
                .doesNotContain("secret-value")
                .doesNotContain("E:\\aicode")
                .doesNotContain("/srv/chongming")
                .doesNotContain("abc.def")
                .contains("[REDACTED]")
                .contains("[HOST_PATH_REDACTED]");
    }

    @Test
    void preservesRawStructuredToolInputAndOutputForTheLiveDebugConversation() {
        RuntimeTraceRedactor.TracePayload input = redactor.rawToolInput(Map.of(
                "pattern", "token=secret-value path=E:\\aicode\\chongming\\pom.xml",
                "api_key", "structured-secret"));
        RuntimeTraceRedactor.TracePayload output = redactor.rawToolOutput(
                "{\"api_key\":\"json-secret\"} bearer abc.def\n" + "x".repeat(5_000));

        assertThat(input.value().toString())
                .contains("secret-value")
                .contains("structured-secret")
                .contains("E:\\aicode");
        assertThat(output.value().toString())
                .contains("abc.def")
                .contains("json-secret")
                .contains("x".repeat(5_000));
        assertThat(output.truncated()).isFalse();
    }

    @Test
    void replacesRuntimeExceptionsWithASafeBrowserSummary() {
        String result = redactor.redactRuntimeError(
                new IllegalStateException("token=secret path=E:\\aicode\\chongming\\application.yml"));

        assertThat(result)
                .doesNotContain("secret")
                .doesNotContain("E:\\aicode")
                .contains("详细异常已隐藏");
    }

}
