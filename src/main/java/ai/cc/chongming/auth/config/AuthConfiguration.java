package ai.cc.chongming.auth.config;

import ai.cc.chongming.auth.api.AuthJwtFilter;
import ai.cc.chongming.auth.application.AuthService;
import ai.cc.chongming.auth.application.JwtTokenService;
import ai.cc.chongming.auth.application.PasswordHasher;
import ai.cc.chongming.auth.domain.UserRepository;
import ai.cc.chongming.auth.infrastructure.InMemoryUserRepository;
import ai.cc.chongming.auth.infrastructure.MyBatisUserRepository;
import ai.cc.chongming.review.infrastructure.persistence.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the authentication module according to the startup contract (see
 * {@link AuthModuleEnabledCondition}): on by default, degraded to disabled with a warning when
 * the JWT secret is missing and the switch was not explicitly enabled. The user store follows
 * the persistence switch: the MyBatis repository is used only while
 * {@code review.persistence.enabled=true} (where the mapper scan and datasource are active),
 * otherwise the in-memory fallback keeps login and registration working.
 *
 * @author wangli
 */
@Configuration(proxyBeanMethods = false)
@Conditional(AuthModuleEnabledCondition.class)
public class AuthConfiguration {

    @Bean
    public PasswordHasher authPasswordHasher() {
        return new PasswordHasher();
    }

    @Bean
    public JwtTokenService authJwtTokenService(AuthProperties properties) {
        return new JwtTokenService(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "true")
    public UserRepository myBatisUserRepository(UserMapper userMapper) {
        return new MyBatisUserRepository(userMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "review.persistence", name = "enabled", havingValue = "false", matchIfMissing = true)
    public UserRepository inMemoryUserRepository() {
        return new InMemoryUserRepository();
    }

    @Bean
    public AuthService authService(UserRepository userRepository, PasswordHasher passwordHasher,
            JwtTokenService jwtTokenService) {
        return new AuthService(userRepository, passwordHasher, jwtTokenService);
    }

    @Bean
    public AuthJwtFilter authJwtFilter(JwtTokenService jwtTokenService, ObjectMapper objectMapper) {
        return new AuthJwtFilter(jwtTokenService, objectMapper);
    }

    /**
     * Registers the JWT filter for servlet requests only and prevents Spring Boot from
     * auto-registering the filter bean a second time.
     *
     * @param authJwtFilter filter bean
     * @return registration scoped to {@code /api/*} REQUEST dispatches
     */
    @Bean
    public FilterRegistrationBean<AuthJwtFilter> authJwtFilterRegistration(AuthJwtFilter authJwtFilter) {
        FilterRegistrationBean<AuthJwtFilter> registration = new FilterRegistrationBean<>(authJwtFilter);
        registration.addUrlPatterns("/api/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setOrder(-100);
        return registration;
    }
}
