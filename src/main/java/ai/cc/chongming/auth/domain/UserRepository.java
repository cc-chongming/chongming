package ai.cc.chongming.auth.domain;

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
     * Persists a fresh user and returns it bound to the generated identifier.
     *
     * @param user unpersisted user
     * @return persisted user with its identifier
     */
    User save(User user);
}
