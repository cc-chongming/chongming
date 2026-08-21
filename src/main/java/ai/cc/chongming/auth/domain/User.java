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
        String role,
        String companyUid,
        String email) {

    /** Upper bound matching the {@code company_uid} persistence column. */
    public static final int MAX_COMPANY_UID_LENGTH = 64;
    /** Upper bound matching the {@code email} persistence column. */
    public static final int MAX_EMAIL_LENGTH = 128;

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
        companyUid = companyUid == null || companyUid.isBlank() ? null : companyUid.trim();
        if (companyUid != null && companyUid.length() > MAX_COMPANY_UID_LENGTH) {
            throw new IllegalArgumentException(
                    "companyUid must be at most " + MAX_COMPANY_UID_LENGTH + " characters");
        }
        // [AIREVIEW-PLAN-030] Optional mail destination used by the notification matrix.
        email = email == null || email.isBlank() ? null : email.trim();
        if (email != null && email.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException("email must be at most " + MAX_EMAIL_LENGTH + " characters");
        }
    }

    /** [AIREVIEW-PLAN-025] Legacy constructor without the company uid. */
    public User(Long id, String username, String passwordHash, String displayName, String role) {
        this(id, username, passwordHash, displayName, role, null, null);
    }

    /** [AIREVIEW-PLAN-030] Legacy constructor without the mail destination. */
    public User(Long id, String username, String passwordHash, String displayName, String role, String companyUid) {
        this(id, username, passwordHash, displayName, role, companyUid, null);
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
        return newUser(username, passwordHash, displayName, role, null);
    }

    /**
     * [AIREVIEW-PLAN-025] Creates a fresh user carrying the optional company-internal uid used
     * later as the message-binding identity.
     *
     * @param companyUid optional company-internal user identifier
     * @return unpersisted user
     */
    public static User newUser(
            String username, String passwordHash, String displayName, String role, String companyUid) {
        return new User(null, username, passwordHash, displayName, role, companyUid);
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
                role,
                companyUid,
                email);
    }

    /**
     * [AIREVIEW-PLAN-030] Returns a copy carrying an updated mail destination.
     *
     * @param email optional mail destination
     * @return user copy with the new contact
     */
    public User withContacts(String email) {
        return new User(id, username, passwordHash, displayName, role, companyUid, email);
    }
}
