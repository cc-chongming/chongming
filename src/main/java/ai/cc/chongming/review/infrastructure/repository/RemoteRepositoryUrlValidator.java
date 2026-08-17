package ai.cc.chongming.review.infrastructure.repository;

import ai.cc.chongming.review.application.RepositoryAccessException;
import ai.cc.chongming.review.application.RepositoryAccessException.Code;
import ai.cc.chongming.review.config.RepositoryAccessProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * [AIREVIEW-PLAN-028] Rejects administrator-configured remote repository URLs that could escape
 * the Git transport or reach unintended hosts. Only {@code https://}, {@code ssh://} and the
 * scp-like {@code git@host:path} forms are accepted; embedded credentials and control characters
 * are always rejected, and resolved hosts on private ranges require an explicit opt-in.
 *
 * @author wangli
 */
@Component
public class RemoteRepositoryUrlValidator {

    /** scp-like remote form such as {@code git@example.com:group/project.git}. */
    private static final Pattern SCP_LIKE = Pattern.compile(
            "^([A-Za-z0-9._-]+)@([A-Za-z0-9.-]+):([^~].*)$");

    private final boolean allowInternalHosts;
    private final boolean allowFileScheme;

    @Autowired
    public RemoteRepositoryUrlValidator(RepositoryAccessProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.allowInternalHosts = Boolean.TRUE.equals(properties.allowInternal());
        this.allowFileScheme = Boolean.TRUE.equals(properties.allowFileScheme());
    }

    /**
     * Test-friendly constructor with explicit switches instead of configuration binding.
     *
     * @param allowInternalHosts permits hosts resolving to loopback/site-local addresses
     * @param allowFileScheme    permits {@code file://} URLs (local bare repositories in tests)
     */
    public RemoteRepositoryUrlValidator(boolean allowInternalHosts, boolean allowFileScheme) {
        this.allowInternalHosts = allowInternalHosts;
        this.allowFileScheme = allowFileScheme;
    }

    /**
     * Validates one configured remote URL before it is handed to the Git transport.
     *
     * @param url administrator-configured remote repository URL
     * @throws RepositoryAccessException with {@link Code#REPOSITORY_PATH_UNSAFE} for unsafe forms,
     *                                   {@link Code#REMOTE_FETCH_FAILED} when the host cannot be resolved
     */
    public void requireSafe(String url) {
        if (url == null || url.isBlank()) {
            throw unsafe("Remote repository URL must not be blank");
        }
        String trimmed = url.trim();
        rejectDangerousCharacters(trimmed);
        if (trimmed.startsWith("https://") || trimmed.startsWith("ssh://")) {
            requireSafeUri(trimmed);
            return;
        }
        if (trimmed.startsWith("file://")) {
            if (!allowFileScheme) {
                throw unsafe("Remote repository URL must use https or ssh");
            }
            requireSafeUri(trimmed);
            return;
        }
        java.util.regex.Matcher matcher = SCP_LIKE.matcher(trimmed);
        if (matcher.matches()) {
            String remotePath = matcher.group(3);
            if (remotePath.contains("..")) {
                throw unsafe("Remote repository path must not traverse parent directories");
            }
            requireResolvableHost(matcher.group(2));
            return;
        }
        throw unsafe("Remote repository URL must use https or ssh");
    }

    private void requireSafeUri(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException exception) {
            throw unsafe("Remote repository URL is malformed");
        }
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            // Windows drive-letter URIs legitimately carry no host (file:///D:/repo.git).
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.isBlank()) {
                throw unsafe("Remote repository URL must name a path");
            }
            if (path.contains("..")) {
                throw unsafe("Remote repository path must not traverse parent directories");
            }
            return;
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw unsafe("Remote repository URL must name a host");
        }
        if (uri.getUserInfo() != null) {
            throw unsafe("Remote repository URL must not embed credentials");
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.contains("..")) {
            throw unsafe("Remote repository path must not traverse parent directories");
        }
        requireResolvableHost(uri.getHost());
    }

    private void requireResolvableHost(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException exception) {
            throw new RepositoryAccessException(
                    Code.REMOTE_FETCH_FAILED, "Remote repository host cannot be resolved");
        }
        if (allowInternalHosts) {
            return;
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
                throw unsafe("Remote repository host resolves to a private address range");
            }
        }
    }

    private void rejectDangerousCharacters(String url) {
        for (int index = 0; index < url.length(); index++) {
            char character = url.charAt(index);
            if (character < 0x20 || character == 0x7f || character == '`' || character == '\\') {
                throw unsafe("Remote repository URL contains unsafe characters");
            }
        }
    }

    private RepositoryAccessException unsafe(String message) {
        return new RepositoryAccessException(Code.REPOSITORY_PATH_UNSAFE, message);
    }
}
