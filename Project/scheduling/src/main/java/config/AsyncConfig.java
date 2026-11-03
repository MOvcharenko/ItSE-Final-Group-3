package config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Runs {@code @Async} methods on Spring Boot's task pool, set up under spring.task.execution. */
@Configuration
@EnableAsync
public class AsyncConfig {
}
