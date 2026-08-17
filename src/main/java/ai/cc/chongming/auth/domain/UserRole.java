package ai.cc.chongming.auth.domain;

/**
 * [AIREVIEW-PLAN-027] Formal authorization roles for the requirement lifecycle. Legacy rows
 * and tokens may still carry the historical {@code USER} role or other unknown values; those
 * always parse to developer-level semantics so unrecognised principals stay on the most
 * restrictive supported permission set.
 *
 * @author wangli
 */
public enum UserRole {

    ADMIN("ADMIN"),
    PRODUCT_MANAGER("PRODUCT_MANAGER"),
    PROJECT_MANAGER("PROJECT_MANAGER"),
    DEVELOPER("DEVELOPER");

    private final String code;

    UserRole(String code) {
        this.code = code;
    }

    /**
     * @return the stored/compared string form of the role
     */
    public String code() {
        return code;
    }

    /**
     * @return whether the role may create requirements; administrators and both manager roles can.
     */
    public boolean canCreateRequirement() {
        return this == ADMIN || this == PRODUCT_MANAGER || this == PROJECT_MANAGER;
    }

    /**
     * @return whether the role sees every requirement regardless of ownership; only administrators do.
     */
    public boolean viewsAllRequirements() {
        return this == ADMIN;
    }

    /**
     * Parses a stored role string into the formal role set. {@code null}, blank values, the
     * legacy {@code USER} role and any unknown value all fall back to {@link #DEVELOPER}, the
     * most restrictive supported role, so permission checks err on the safe side.
     *
     * @param value raw role string from a user row or JWT claim
     * @return matching formal role, developer-level semantics otherwise
     */
    public static UserRole parse(String value) {
        if (value == null || value.isBlank()) {
            return DEVELOPER;
        }
        String trimmed = value.trim();
        for (UserRole role : values()) {
            if (role.code.equals(trimmed)) {
                return role;
            }
        }
        return DEVELOPER;
    }
}
