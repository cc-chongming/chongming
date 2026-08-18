package ai.cc.chongming.auth.infrastructure;

import ai.cc.chongming.auth.domain.AuthErrorCode;
import ai.cc.chongming.auth.domain.AuthException;
import ai.cc.chongming.auth.domain.User;
import ai.cc.chongming.auth.domain.UserRepository;
import ai.cc.chongming.review.infrastructure.persistence.mapper.UserMapper;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;

/**
 * MySQL-backed user repository wired only when {@code review.persistence.enabled=true}.
 * The unique username constraint is enforced by the database; a concurrent duplicate insert is
 * translated into the stable {@link AuthErrorCode#USERNAME_TAKEN} error.
 *
 * @author wangli
 */
public class MyBatisUserRepository implements UserRepository {

    private final UserMapper mapper;

    public MyBatisUserRepository(UserMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return Optional.ofNullable(mapper.findByUsername(username)).map(this::toUser);
    }

    @Override
    public Optional<User> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.findById(id)).map(this::toUser);
    }

    /** [AIREVIEW-PLAN-025] Company-internal uid lookup backing registration uniqueness checks. */
    @Override
    public Optional<User> findByCompanyUid(String companyUid) {
        if (companyUid == null || companyUid.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.findByCompanyUid(companyUid)).map(this::toUser);
    }

    @Override
    public User save(User user) {
        User nonNullUser = Objects.requireNonNull(user, "user must not be null");
        UserMapper.UserRow row = new UserMapper.UserRow(
                null,
                nonNullUser.username(),
                nonNullUser.passwordHash(),
                nonNullUser.displayName(),
                nonNullUser.role(),
                nonNullUser.companyUid());
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException ex) {
            // The users table carries two unique constraints; a duplicate company uid surfaces
            // as the stable UID_TAKEN error, everything else as USERNAME_TAKEN.
            if (nonNullUser.companyUid() != null
                    && mapper.findByCompanyUid(nonNullUser.companyUid()) != null) {
                throw new AuthException(AuthErrorCode.UID_TAKEN, "公司 UID 已被其他账号绑定");
            }
            throw new AuthException(AuthErrorCode.USERNAME_TAKEN, "用户名已被占用");
        }
        // UserRow is immutable, so the generated key cannot be written back by MyBatis;
        // re-read the persisted row through the unique username to obtain the assigned id.
        UserMapper.UserRow stored = mapper.findByUsername(nonNullUser.username());
        if (stored == null || stored.id() == null) {
            throw new IllegalStateException("user row vanished after insert: " + nonNullUser.username());
        }
        return toUser(stored);
    }

    @Override
    public List<UserView> findAll() {
        return mapper.findAll().stream()
                .map(row -> new UserView(row.username(), row.displayName(), row.role(), row.companyUid()))
                .toList();
    }

    private User toUser(UserMapper.UserRow row) {
        return new User(
                row.id(), row.username(), row.passwordHash(), row.displayName(), row.role(), row.companyUid());
    }
}
