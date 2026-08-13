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

    @Override
    public User save(User user) {
        User nonNullUser = Objects.requireNonNull(user, "user must not be null");
        UserMapper.UserRow row = new UserMapper.UserRow(
                null,
                nonNullUser.username(),
                nonNullUser.passwordHash(),
                nonNullUser.displayName(),
                nonNullUser.role());
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException ex) {
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
                .map(row -> new UserView(row.username(), row.displayName(), row.role()))
                .toList();
    }

    private User toUser(UserMapper.UserRow row) {
        return new User(row.id(), row.username(), row.passwordHash(), row.displayName(), row.role());
    }
}
