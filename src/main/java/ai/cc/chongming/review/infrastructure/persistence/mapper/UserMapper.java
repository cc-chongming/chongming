package ai.cc.chongming.review.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MyBatis statements for the authentication user table. Lives in this package on purpose so the
 * existing {@code @MapperScan} in ReviewPersistenceConfiguration picks it up without any change
 * to that wiring.
 *
 * @author wangli
 */
public interface UserMapper {

    // The generated key is not written back on purpose: UserRow is an immutable record, so
    // callers re-read the row by the unique username instead of relying on keyProperty backfill.
    @Insert("""
            INSERT INTO users (username, password_hash, display_name, role)
            VALUES (#{username}, #{passwordHash}, #{displayName}, #{role})
            """)
    int insert(UserRow row);

    @Select("""
            SELECT id, username, password_hash AS passwordHash, display_name AS displayName, role
            FROM users WHERE username = #{username}
            """)
    UserRow findByUsername(@Param("username") String username);

    @Select("""
            SELECT id, username, password_hash AS passwordHash, display_name AS displayName, role
            FROM users WHERE id = #{id}
            """)
    UserRow findById(@Param("id") long id);

    /**
     * Flat row shape for the {@code users} table.
     *
     * @author wangli
     */
    record UserRow(
            Long id,
            String username,
            String passwordHash,
            String displayName,
            String role) {
    }
}
