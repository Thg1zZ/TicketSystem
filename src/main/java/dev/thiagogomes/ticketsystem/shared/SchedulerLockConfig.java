package dev.thiagogomes.ticketsystem.shared;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * With several instances, @SchedulerLock jobs run on one of them per interval. If the holder dies, the lock
 * expires after lockAtMostFor and another instance takes over.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
class SchedulerLockConfig {

    @Bean
    LockProvider lockProvider(RedisConnectionFactory redis) {
        return new RedisLockProvider(redis, "ticket-system");
    }
}
