package app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. The layers are flat top-level packages, so they are listed explicitly:
 * add a new top-level package here or Spring will not find its beans.
 * JPA entities and repositories are looked up in {@code persistence}.
 */
@SpringBootApplication(scanBasePackages = {
		"api", "config", "core", "domain", "integration", "persistence", "security", "service" })
@AutoConfigurationPackage(basePackages = "persistence")
public class SchedulingApplication {

	public static void main(String[] args) {
		SpringApplication.run(SchedulingApplication.class, args);
	}

}
