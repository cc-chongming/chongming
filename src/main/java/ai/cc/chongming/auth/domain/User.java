package ai.cc.chongming.auth.domain;

import java.util.Objects;

/**
 * Immutable user aggregate for the authentication module. The {@code id} is null
 * until the user is persisted, mirroring how the review domain models fresh aggregates.
 *
 * @author wangli
 */
public record User(
        Long id,
        String username,
        String passwordHash,
        String displayName,
        String role) {

    public User {
        username = Objects.requireNonNull(username, "username must not be null");
        if (username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        passwordHash = Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        role = Objects.requireNonNull(role, "role must not be null");
        if (role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
    }

    /**
     * Creates a fresh user without a persistence identifier.
     *
     * @param username     unique login name
     * @param passwordHash stored credential hash
     * @param displayName  optional display name
     * @param role         authorization role
     * @return unpersisted user
     */
    public static User newUser(String username, String passwordHash, String displayName, String role) {
        return new User(null, username, passwordHash, displayName, role);
    }

    /**
     * Returns a copy carrying the persistence identifier assigned by the store.
     *
     * @param assignedId generated identifier
     * @return user bound to its identifier
     */
    public User withId(Long assignedId) {
        return new User(
                Objects.requireNonNull(assignedId, "assignedId must not be null"),
                username,
                passwordHash,
                displayName,
                role);
    }
}
