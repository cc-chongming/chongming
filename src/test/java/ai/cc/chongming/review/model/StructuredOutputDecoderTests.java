package ai.cc.chongming.review.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.review.infrastructure.model.StructuredOutputDecoder;
import ai.cc.chongming.review.infrastructure.model.StructuredOutputException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests strict structured-output decoding and its single repair boundary.
 *
 * @author wangli
 */
class StructuredOutputDecoderTests {

    private final StructuredOutputDecoder decoder = new StructuredOutputDecoder(new ObjectMapper());

    @Test
    void decodesValidPlanJsonWithoutRepair() {
        var decoded = decoder.decodePlan("""
                {"tasks":[{"title":"Inspect boundary","reason":"Verify path safety"}],"changeReason":"Initial plan"}
                """, null);

        assertThat(decoded.repaired()).isFalse();
        assertThat(decoded.value().tasks()).singleElement()
                .extracting(task -> task.title(), task -> task.reason())
                .containsExactly("Inspect boundary", "Verify path safety");
        assertThat(decoded.contentHash()).hasSize(64);
    }

    @Test
    void permitsExactlyOneRepairAndRejectsRemainingInvalidJson() {
        AtomicInteger repairs = new AtomicInteger();
        var repaired = decoder.decodePlan("not-json", (kind, invalid, code) -> {
            repairs.incrementAndGet();
            return "{\"tasks\":[{\"title\":\"Repair\",\"reason\":\"JSON only\"}],\"changeReason\":\"Fixed\"}";
        });

        assertThat(repaired.repaired()).isTrue();
        assertThat(repairs).hasValue(1);
        assertThatThrownBy(() -> decoder.decodePlan("not-json", (kind, invalid, code) -> "still-invalid"))
                .isInstanceOf(StructuredOutputException.class)
                .extracting(error -> ((StructuredOutputException) error).code())
                .isEqualTo(StructuredOutputException.Code.REPAIR_FAILED);
    }

    @Test
    void rejectsUnknownFieldsInsteadOfGuessingBusinessValues() {
        assertThatThrownBy(() -> decoder.decodePlan("""
                {"tasks":[{"title":"Inspect","reason":"Safe"}],"changeReason":"Initial","freeText":"ignore"}
                """, null))
                .isInstanceOf(StructuredOutputException.class);
    }
}
