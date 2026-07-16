package ai.cc.chongming.review.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the JSON mapper required by the model gateway when the host application does not
 * enable Spring Boot's Jackson auto-configuration.
 *
 * @author wangli
 */
@Configuration(proxyBeanMethods = false)
public class ModelGatewayJacksonConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper modelGatewayObjectMapper() {
        return new ObjectMapper();
    }
}
