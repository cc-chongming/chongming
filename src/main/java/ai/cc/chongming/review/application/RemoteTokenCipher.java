package ai.cc.chongming.review.application;

import ai.cc.chongming.review.config.RemoteTokenProperties;
import ai.cc.chongming.review.domain.exception.RequirementDomainException;
import ai.cc.chongming.review.domain.exception.RequirementErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * [AIREVIEW-PLAN-029] Encrypts requirement-supplied remote repository tokens before persistence
 * and decrypts them only at snapshot materialization time. Cipher text uses the stable
 * {@code v1:base64(iv || AES-GCM payload)} envelope; the plain-text token never survives a call.
 *
 * @author wangli
 */
@Service
public class RemoteTokenCipher {

    private static final String ENVELOPE_PREFIX = "v1:";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MAX_PLAIN_TOKEN_LENGTH = 512;

    private final byte[] keyBytes;
    private final SecureRandom secureRandom = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public RemoteTokenCipher(RemoteTokenProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.keyBytes = properties.key() == null ? null : deriveKey(properties.key());
    }

    /** Test constructor bypassing configuration binding. */
    public RemoteTokenCipher(String rawKey) {
        this.keyBytes = rawKey == null || rawKey.isBlank() ? null : deriveKey(rawKey);
    }

    /**
     * @return whether the deployment supplied an encryption key; without one every token-bearing
     *         remote requirement is rejected with a stable error instead of degrading silently.
     */
    public boolean isConfigured() {
        return keyBytes != null;
    }

    /**
     * Encrypts one plain-text access token into the stable envelope form.
     *
     * @param plainToken caller-supplied token, never persisted or logged
     * @return cipher text safe to persist beside the requirement
     */
    public String encrypt(String plainToken) {
        Objects.requireNonNull(plainToken, "plainToken must not be null");
        if (plainToken.isBlank()) {
            throw new IllegalArgumentException("plainToken must not be blank");
        }
        if (plainToken.length() > MAX_PLAIN_TOKEN_LENGTH) {
            throw new RequirementDomainException(
                    RequirementErrorCode.REMOTE_SOURCE_INVALID, "远程仓库令牌长度超出限制");
        }
        requireConfigured();
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] payload = cipher.doFinal(plainToken.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[iv.length + payload.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(payload, 0, envelope, iv.length, payload.length);
            return ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException exception) {
            throw new RequirementDomainException(
                    RequirementErrorCode.REMOTE_SOURCE_INVALID, "远程仓库令牌加密失败");
        }
    }

    /**
     * Decrypts one persisted envelope back into the plain-text token for a single Git command.
     *
     * @param encryptedToken cipher text produced by {@link #encrypt(String)}
     * @return plain-text token, never persisted or logged by callers
     */
    public String decrypt(String encryptedToken) {
        if (encryptedToken == null || encryptedToken.isBlank()) {
            return null;
        }
        requireConfigured();
        if (!encryptedToken.startsWith(ENVELOPE_PREFIX)) {
            throw new RequirementDomainException(
                    RequirementErrorCode.REMOTE_SOURCE_INVALID, "远程仓库令牌密文格式非法");
        }
        try {
            byte[] envelope = Base64.getDecoder().decode(encryptedToken.substring(ENVELOPE_PREFIX.length()));
            if (envelope.length <= GCM_IV_LENGTH) {
                throw new RequirementDomainException(
                        RequirementErrorCode.REMOTE_SOURCE_INVALID, "远程仓库令牌密文格式非法");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, envelope, 0, GCM_IV_LENGTH));
            return new String(
                    cipher.doFinal(envelope, GCM_IV_LENGTH, envelope.length - GCM_IV_LENGTH), StandardCharsets.UTF_8);
        } catch (RequirementDomainException exception) {
            throw exception;
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new RequirementDomainException(
                    RequirementErrorCode.REMOTE_SOURCE_INVALID, "远程仓库令牌无法解密");
        }
    }

    private void requireConfigured() {
        if (keyBytes == null) {
            throw new RequirementDomainException(
                    RequirementErrorCode.REMOTE_SOURCE_INVALID,
                    "服务端未配置远程令牌加密密钥（REVIEW_REMOTE_TOKEN_KEY），无法保存线上仓库令牌");
        }
    }

    private static byte[] deriveKey(String rawKey) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(rawKey.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
