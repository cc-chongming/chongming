package ai.cc.chongming.review.domain.model;

import java.util.Objects;

/**
 * [AIREVIEW-PLAN-029] Requirement-supplied online repository source. The token is only ever held
 * as cipher text produced by the application-layer token cipher; plain-text credentials never
 * enter the domain model, read projections, manifests or logs.
 *
 * @author wangli
 */
public record RemoteRepositorySource(String url, String ref, String encryptedToken) {

    /** Upper bound matching the {@code remote_url} persistence column. */
    public static final int MAX_URL_LENGTH = 512;
    /** Upper bound matching the {@code remote_ref} persistence column. */
    public static final int MAX_REF_LENGTH = 128;
    /** Upper bound matching the {@code remote_token_enc} persistence column. */
    public static final int MAX_ENCRYPTED_TOKEN_LENGTH = 1024;

    public RemoteRepositorySource {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("remote repository url must not be blank");
        }
        if (url.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("remote repository url must be at most " + MAX_URL_LENGTH + " characters");
        }
        url = url.trim();
        ref = ref == null || ref.isBlank() ? null : ref.trim();
        if (ref != null && ref.length() > MAX_REF_LENGTH) {
            throw new IllegalArgumentException("remote repository ref must be at most " + MAX_REF_LENGTH + " characters");
        }
        encryptedToken = encryptedToken == null || encryptedToken.isBlank() ? null : encryptedToken.trim();
        if (encryptedToken != null && encryptedToken.length() > MAX_ENCRYPTED_TOKEN_LENGTH) {
            throw new IllegalArgumentException(
                    "remote repository encrypted token must be at most " + MAX_ENCRYPTED_TOKEN_LENGTH + " characters");
        }
    }

    /**
     * @return the source with its encrypted token replaced by {@code replacement}, used when a
     *         revision supplies a fresh token or explicitly keeps the previous one.
     */
    public RemoteRepositorySource withEncryptedToken(String replacement) {
        return new RemoteRepositorySource(url, ref, replacement);
    }

    /**
     * @return stable content identity used for mirror directory naming and snapshot keys; the
     *         token deliberately takes no part so credential rotation never forks mirrors.
     */
    public String identitySeed() {
        return url + '\u0000' + (ref == null ? "" : ref);
    }

    /**
     * @return repository identity string recorded on snapshots and references, stable across
     *         credential rotation for the same url and ref.
     */
    public String repositoryIdentity() {
        StringBuilder hex = new StringBuilder("remote:");
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(identitySeed().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
        return hex.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RemoteRepositorySource source
                && url.equals(source.url)
                && Objects.equals(ref, source.ref)
                && Objects.equals(encryptedToken, source.encryptedToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url, ref, encryptedToken);
    }
}
