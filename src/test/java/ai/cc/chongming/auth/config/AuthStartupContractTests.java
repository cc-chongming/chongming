package ai.cc.chongming.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cc.chongming.auth.config.AuthStartupContract.AssemblyDecision;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

/**
 * Unit tests for the authentication startup contract: secret usability, the assembly decision
 * matrix and the detection of an explicitly enabled switch outside packaged defaults.
 *
 * @author wangli
 */
class AuthStartupContractTests {

    private static final String USABLE_SECRET = "chongming-test-jwt-secret-0123456789abcdef";

    @Test
    void jwtSecretUsableRequiresAtLeast32Utf8Bytes() {
        assertThat(AuthStartupContract.jwtSecretUsable(null)).isFalse();
        assertThat(AuthStartupContract.jwtSecretUsable("")).isFalse();
        assertThat(AuthStartupContract.jwtSecretUsable("   ")).isFalse();
        assertThat(AuthStartupContract.jwtSecretUsable("a".repeat(31))).isFalse();
        assertThat(AuthStartupContract.jwtSecretUsable("a".repeat(32))).isTrue();
        assertThat(AuthStartupContract.jwtSecretUsable(USABLE_SECRET)).isTrue();
    }

    @Test
    void usableSecretKeepsTheModuleEnabled() {
        assertThat(AuthStartupContract.decideAssembly(true, false, USABLE_SECRET))
                .isEqualTo(AssemblyDecision.ENABLED);
    }

    @Test
    void disabledSwitchSkipsTheModuleEvenWithAUsableSecret() {
        assertThat(AuthStartupContract.decideAssembly(false, false, USABLE_SECRET))
                .isEqualTo(AssemblyDecision.DISABLED);
        assertThat(AuthStartupContract.decideAssembly(false, true, USABLE_SECRET))
                .isEqualTo(AssemblyDecision.DISABLED);
    }

    @Test
    void defaultSwitchWithMissingOrShortSecretDegradesInsteadOfFailing() {
        assertThat(AuthStartupContract.decideAssembly(true, false, null))
                .isEqualTo(AssemblyDecision.DISABLED);
        assertThat(AuthStartupContract.decideAssembly(true, false, ""))
                .isEqualTo(AssemblyDecision.DISABLED);
        assertThat(AuthStartupContract.decideAssembly(true, false, "too-short"))
                .isEqualTo(AssemblyDecision.DISABLED);
        assertThat(AuthStartupContract.decideAssembly(null, false, null))
                .isEqualTo(AssemblyDecision.DISABLED);
    }

    @Test
    void explicitSwitchWithMissingSecretFailsFast() {
        assertThat(AuthStartupContract.decideAssembly(true, true, null))
                .isEqualTo(AssemblyDecision.FAIL_FAST);
        assertThat(AuthStartupContract.decideAssembly(true, true, "too-short"))
                .isEqualTo(AssemblyDecision.FAIL_FAST);
    }

    @Test
    void packagedDefaultSwitchIsNotTreatedAsExplicit() {
        StandardEnvironment environment = bareEnvironment();
        MockPropertySource packagedDefaults = new MockPropertySource(
                "Config resource 'class path resource [application.yml]' via location 'optional:classpath:/'");
        packagedDefaults.setProperty(AuthStartupContract.ENABLED_PROPERTY, "true");
        environment.getPropertySources().addLast(packagedDefaults);

        assertThat(AuthStartupContract.resolveExplicitlyEnabled(environment)).isFalse();
    }

    @Test
    void operatorPropertySourceCountsAsExplicit() {
        StandardEnvironment environment = bareEnvironment();
        MockPropertySource operator = new MockPropertySource("operatorOverrides");
        operator.setProperty(AuthStartupContract.ENABLED_PROPERTY, "true");
        environment.getPropertySources().addLast(operator);

        assertThat(AuthStartupContract.resolveExplicitlyEnabled(environment)).isTrue();
    }

    @Test
    void environmentVariableMirrorCountsAsExplicit() {
        StandardEnvironment environment = bareEnvironment();
        MockPropertySource operatorEnvironment = new MockPropertySource("operatorEnvironment");
        operatorEnvironment.setProperty(AuthStartupContract.ENABLED_ENVIRONMENT_VARIABLE, "false");
        environment.getPropertySources().addLast(operatorEnvironment);

        assertThat(AuthStartupContract.resolveExplicitlyEnabled(environment)).isFalse();
    }

    private StandardEnvironment bareEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return environment;
    }
}
