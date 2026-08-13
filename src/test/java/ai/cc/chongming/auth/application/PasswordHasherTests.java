package ai.cc.chongming.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Behavior tests for the PBKDF2 password hashing and verification contract.
 *
 * @author wangli
 */
class PasswordHasherTests {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void verifiesCorrectPasswordAgainstStoredHash() {
        String stored = hasher.hash("Admin@123");

        assertThat(hasher.verify("Admin@123", stored)).isTrue();
    }

    @Test
    void producesPortableFormatWithAtLeastMinimumIterations() {
        String stored = hasher.hash("Admin@123");

        String[] parts = stored.split("\\$");
        assertThat(parts).hasSize(4);
        assertThat(parts[0]).isEqualTo("PBKDF2");
        assertThat(Integer.parseInt(parts[1])).isGreaterThanOrEqualTo(PasswordHasher.MIN_ITERATIONS);
    }

    @Test
    void rejectsWrongPassword() {
        String stored = hasher.hash("Admin@123");

        assertThat(hasher.verify("wrong-password", stored)).isFalse();
    }

    @Test
    void rejectsTamperedHash() {
        String stored = hasher.hash("Admin@123");
        // Flip a character in the derived-hash segment so the digest no longer matches.
        char last = stored.charAt(stored.length() - 1);
        char swapped = last == 'A' ? 'B' : 'A';
        String tampered = stored.substring(0, stored.length() - 1) + swapped;

        assertThat(hasher.verify("Admin@123", tampered)).isFalse();
    }

    @Test
    void rejectsMalformedStoredHashInsteadOfThrowing() {
        assertThat(hasher.verify("Admin@123", "not-a-pbkdf2-value")).isFalse();
        assertThat(hasher.verify("Admin@123", null)).isFalse();
        assertThat(hasher.verify("Admin@123", "PBKDF2$abc$salt$hash")).isFalse();
    }

    @Test
    void generatesDistinctSaltsForRepeatedHashes() {
        assertThat(hasher.hash("Admin@123")).isNotEqualTo(hasher.hash("Admin@123"));
    }

    @Test
    void rejectsIterationCountBelowRecommendedFloor() {
        assertThatThrownBy(() -> new PasswordHasher(1_000)).isInstanceOf(IllegalArgumentException.class);
    }
}
