package ai.cc.chongming.review.infrastructure.persistence.mapper;

import java.util.List;

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
            INSERT INTO users (username, password_hash, display_name, role, company_uid, email)
            VALUES (#{username}, #{passwordHash}, #{displayName}, #{role}, #{companyUid}, #{email})
            """)
    int insert(UserRow row);

    /** [AIREVIEW-PLAN-030] Self-service mail destination update. */
    @org.apache.ibatis.annotations.Update("""
            UPDATE users SET email = #{email} WHERE username = #{username}
            """)
    int updateContacts(@Param("username") String username, @Param("email") String email);

    @Select("""
            SELECT id, username, password_hash AS passwordHash, display_name AS displayName, role,
                   company_uid AS companyUid, email
            FROM users WHERE username = #{username}
            """)
    UserRow findByUsername(@Param("username") String username);

    @Select("""
            SELECT id, username, password_hash AS passwordHash, display_name AS displayName, role,
                   company_uid AS companyUid, email
            FROM users WHERE id = #{id}
            """)
    UserRow findById(@Param("id") long id);

    /** [AIREVIEW-PLAN-025] Looks up one user by its optional unique company-internal uid. */
    @Select("""
            SELECT id, username, password_hash AS passwordHash, display_name AS displayName, role,
                   company_uid AS companyUid, email
            FROM users WHERE company_uid = #{companyUid}
            """)
    UserRow findByCompanyUid(@Param("companyUid") String companyUid);

    // Credential-free directory read: deliberately excludes password_hash.
    @Select("""
            SELECT id, username, display_name AS displayName, role, company_uid AS companyUid, email
            FROM users ORDER BY username ASC
            """)
    List<UserListRow> findAll();

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
            String role,
            String companyUid,
            String email) {
    }

    /**
     * Credential-free row shape for directory reads of the {@code users} table.
     *
     * @author wangli
     */
    record UserListRow(
            Long id,
            String username,
            String displayName,
            String role,
            String companyUid,
            String email) {
    }
}
