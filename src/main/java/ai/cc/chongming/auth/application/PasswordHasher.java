package ai.cc.chongming.auth.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Hashes and verifies passwords with PBKDF2-HMAC-SHA256. Stored hashes use the portable
 * format {@code PBKDF2$iterations$saltBase64$hashBase64} so a migration seed and runtime
 * registrations stay verifiable by the same logic. Iterations of existing hashes are honored
 * on verify, while new hashes always use at least {@link #MIN_ITERATIONS}.
 *
 * @author wangli
 */
public final class PasswordHasher {

    /** OWASP-recommended lower bound for PBKDF2-HMAC-SHA256. */
    public static final int MIN_ITERATIONS = 210_000;

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String FORMAT_PREFIX = "PBKDF2";
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int HASH_LENGTH_BITS = 256;

    private final int iterations;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordHasher() {
        this(MIN_ITERATIONS);
    }

    public PasswordHasher(int iterations) {
        if (iterations < MIN_ITERATIONS) {
            throw new IllegalArgumentException(
                    "iterations must be at least " + MIN_ITERATIONS + " but was " + iterations);
        }
        this.iterations = iterations;
    }

    /**
     * Derives a fresh salted hash for a raw password.
     *
     * @param rawPassword plaintext password
     * @return portable hash string
     */
    public String hash(String rawPassword) {
        Objects.requireNonNull(rawPassword, "rawPassword must not be null");
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        secureRandom.nextBytes(salt);
        byte[] hash = derive(rawPassword.toCharArray(), salt, iterations);
        Base64.Encoder encoder = Base64.getEncoder();
        return FORMAT_PREFIX + "$" + iterations + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(hash);
    }

    /**
     * Verifies a raw password against a stored hash. Any malformed stored value is rejected
     * instead of raising, so a corrupted row behaves like a wrong password.
     *
     * @param rawPassword plaintext password attempt
     * @param storedHash  stored hash in {@code PBKDF2$...} format
     * @return true when the password matches
     */
    public boolean verify(String rawPassword, String storedHash) {
        Objects.requireNonNull(rawPassword, "rawPassword must not be null");
        if (storedHash == null) {
            return false;
        }
        String[] parts = storedHash.split("\\$");
        if (parts.length != 4 || !FORMAT_PREFIX.equals(parts[0])) {
            return false;
        }
        int storedIterations;
        byte[] salt;
        byte[] expectedHash;
        try {
            storedIterations = Integer.parseInt(parts[1]);
            salt = Base64.getDecoder().decode(parts[2]);
            expectedHash = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        if (storedIterations < 1 || salt.length == 0 || expectedHash.length == 0) {
            return false;
        }
        byte[] candidate = derive(rawPassword.toCharArray(), salt, storedIterations);
        return MessageDigest.isEqual(expectedHash, candidate);
    }

    private byte[] derive(char[] password, byte[] salt, int iterationCount) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterationCount, HASH_LENGTH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException("PBKDF2-HMAC-SHA256 is unavailable on this JVM", ex);
        } finally {
            spec.clearPassword();
        }
    }
}
