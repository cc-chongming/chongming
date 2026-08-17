package ai.cc.chongming.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * [AIREVIEW-PLAN-027] Guards the formal role set: parse fallback semantics for legacy or
 * unknown stored values and the two authorization predicates driving requirement visibility
 * and creation.
 *
 * @author wangli
 */
class UserRoleTests {

    @Test
    void parsesEveryFormalRoleFromItsStoredCode() {
        assertThat(UserRole.parse("ADMIN")).isEqualTo(UserRole.ADMIN);
        assertThat(UserRole.parse("PRODUCT_MANAGER")).isEqualTo(UserRole.PRODUCT_MANAGER);
        assertThat(UserRole.parse("PROJECT_MANAGER")).isEqualTo(UserRole.PROJECT_MANAGER);
        assertThat(UserRole.parse("DEVELOPER")).isEqualTo(UserRole.DEVELOPER);
        assertThat(UserRole.parse("  ADMIN  ")).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void fallsBackToDeveloperSemanticsForLegacyNullOrUnknownValues() {
        assertThat(UserRole.parse(null)).isEqualTo(UserRole.DEVELOPER);
        assertThat(UserRole.parse("")).isEqualTo(UserRole.DEVELOPER);
        assertThat(UserRole.parse("   ")).isEqualTo(UserRole.DEVELOPER);
        // Historical rows and tokens still carry the legacy USER role; it must never unlock
        // administrator or manager privileges.
        assertThat(UserRole.parse("USER")).isEqualTo(UserRole.DEVELOPER);
        assertThat(UserRole.parse("SUPER_ADMIN")).isEqualTo(UserRole.DEVELOPER);
        assertThat(UserRole.parse("admin")).isEqualTo(UserRole.DEVELOPER);
    }

    @Test
    void onlyManagersAndAdministratorsMayCreateRequirements() {
        assertThat(UserRole.ADMIN.canCreateRequirement()).isTrue();
        assertThat(UserRole.PRODUCT_MANAGER.canCreateRequirement()).isTrue();
        assertThat(UserRole.PROJECT_MANAGER.canCreateRequirement()).isTrue();
        assertThat(UserRole.DEVELOPER.canCreateRequirement()).isFalse();
        assertThat(UserRole.parse("USER").canCreateRequirement()).isFalse();
    }

    @Test
    void onlyAdministratorsSeeEveryRequirement() {
        assertThat(UserRole.ADMIN.viewsAllRequirements()).isTrue();
        assertThat(UserRole.PRODUCT_MANAGER.viewsAllRequirements()).isFalse();
        assertThat(UserRole.PROJECT_MANAGER.viewsAllRequirements()).isFalse();
        assertThat(UserRole.DEVELOPER.viewsAllRequirements()).isFalse();
        assertThat(UserRole.parse(null).viewsAllRequirements()).isFalse();
    }

    @Test
    void codeRoundTripsTheStoredStringForm() {
        for (UserRole role : UserRole.values()) {
            assertThat(UserRole.parse(role.code())).isEqualTo(role);
        }
    }
}
