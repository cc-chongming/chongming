package ai.cc.chongming.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.exception.RequirementErrorCode;
import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-029] Verifies the AES-GCM envelope protecting requirement-supplied remote
 * repository tokens: round-trip integrity, distinct cipher texts per encryption and stable
 * rejections when the key is missing or the envelope is tampered with.
 *
 * @author wangli
 */
class RemoteTokenCipherTests {

    private final RemoteTokenCipher cipher = new RemoteTokenCipher("plan-029-test-key");

    @Test
    void encryptsAndDecryptsTokensThroughARoundTrip() {
        String encrypted = cipher.encrypt("ghp_demo_token_value");

        assertThat(encrypted).startsWith("v1:").isNotEqualTo("ghp_demo_token_value");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("ghp_demo_token_value");
    }

    @Test
    void producesDistinctCipherTextsForRepeatedEncryption() {
        String first = cipher.encrypt("same-token");
        String second = cipher.encrypt("same-token");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("same-token");
        assertThat(cipher.decrypt(second)).isEqualTo("same-token");
    }

    @Test
    void decryptsNullAndBlankCipherTextToNull() {
        assertThat(cipher.decrypt(null)).isNull();
        assertThat(cipher.decrypt("  ")).isNull();
    }

    @Test
    void rejectsEncryptionWhenTheKeyIsNotConfigured() {
        RemoteTokenCipher unconfigured = new RemoteTokenCipher((String) null);

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.encrypt("token"))
                .isInstanceOf(RequirementDomainException.class)
                .extracting(exception -> ((RequirementDomainException) exception).errorCode())
                .isEqualTo(RequirementErrorCode.REMOTE_SOURCE_INVALID);
    }

    @Test
    void rejectsTamperedCipherTextWithAStableError() {
        String encrypted = cipher.encrypt("secret-token");
        char flipped = encrypted.charAt(encrypted.length() - 2) == 'A' ? 'B' : 'A';
        String tampered = encrypted.substring(0, encrypted.length() - 2) + flipped + encrypted.charAt(encrypted.length() - 1);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(RequirementDomainException.class)
                .extracting(exception -> ((RequirementDomainException) exception).errorCode())
                .isEqualTo(RequirementErrorCode.REMOTE_SOURCE_INVALID);
    }

    @Test
    void rejectsCipherTextFromAnotherKey() {
        String encrypted = cipher.encrypt("secret-token");
        RemoteTokenCipher otherCipher = new RemoteTokenCipher("another-key");

        assertThatThrownBy(() -> otherCipher.decrypt(encrypted))
                .isInstanceOf(RequirementDomainException.class)
                .extracting(exception -> ((RequirementDomainException) exception).errorCode())
                .isEqualTo(RequirementErrorCode.REMOTE_SOURCE_INVALID);
    }

    @Test
    void rejectsOverlongPlainTokens() {
        assertThatThrownBy(() -> cipher.encrypt("t".repeat(600)))
                .isInstanceOf(RequirementDomainException.class)
                .extracting(exception -> ((RequirementDomainException) exception).errorCode())
                .isEqualTo(RequirementErrorCode.REMOTE_SOURCE_INVALID);
    }
}
