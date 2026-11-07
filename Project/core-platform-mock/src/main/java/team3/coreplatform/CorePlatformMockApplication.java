package main.java.team3.coreplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Stand-in Core Platform. Issues signed JWTs to three fixed test accounts so the
 * Academic Scheduling module can be demonstrated with real OIDC sign-in. Not the
 * real thing: no passwords, no user management, no administrator functions.
 */
@SpringBootApplication
public class CorePlatformMockApplication {

	public static void main(String[] args) {
		SpringApplication.run(CorePlatformMockApplication.class, args);
	}

}