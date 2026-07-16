package ai.cc.chongming;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;
/**
 * [AIREVIEW-PLAN-010#1.4] Boots scheduled SSE heartbeats and review application components.
 *
 * @author wangli
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ChongmingApplication {

	public static void main(String[] args) {
		SpringApplication.run(ChongmingApplication.class, args);
	}

}
