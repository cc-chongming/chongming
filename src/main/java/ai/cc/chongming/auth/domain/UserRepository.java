package ai.cc.chongming.auth.domain;

import java.util.List;
import java.util.Optional;

/**
 * Persistence abstraction for user accounts. Implementations are selected at startup:
 * the MyBatis store when {@code review.persistence.enabled=true}, otherwise the in-memory
 * fallback so the authentication flow keeps working without a database.
 *
 * @author wangli
 */
public interface UserRepository {

    /**
     * Looks up a user by its unique login name.
     *
     * @param username login name
     * @return matching user when present
     */
    Optional<User> findByUsername(String username);

    /**
     * Looks up a user by its persistence identifier.
     *
     * @param id generated identifier
     * @return matching user when present
     */
    Optional<User> findById(Long id);

    /**
     * [AIREVIEW-PLAN-025] Looks up a user by its optional company-internal uid.
     *
     * @param companyUid company-internal user identifier
     * @return matching user when present
     */
    Optional<User> findByCompanyUid(String companyUid);

    /**
     * Persists a fresh user and returns it bound to the generated identifier.
     *
     * @param user unpersisted user
     * @return persisted user with its identifier
     */
    User save(User user);

    /**
     * [AIREVIEW-PLAN-030] Updates the optional mail destination of an existing account.
     *
     * @param username login name of the account to update
     * @param email    optional mail destination, blank clears
     * @return the updated user when present, empty otherwise
     */
    Optional<User> updateContacts(String username, String email);

    /**
     * Lists every account as a credential-free projection; implementations must not load
     * the stored password hash.
     *
     * @return all users ordered by username
     */
    List<UserView> findAll();

    /**
     * Credential-free user projection exposed to directory reads.
     * [AIREVIEW-PLAN-025] Carries the optional company uid for message binding.
     *
     * @author wangli
     */
    record UserView(String username, String displayName, String role, String companyUid, String email) {

        /** Legacy projection without the company uid. */
        public UserView(String username, String displayName, String role) {
            this(username, displayName, role, null, null);
        }

        /** [AIREVIEW-PLAN-025] Legacy projection without the mail destination. */
        public UserView(String username, String displayName, String role, String companyUid) {
            this(username, displayName, role, companyUid, null);
        }
    }
}
