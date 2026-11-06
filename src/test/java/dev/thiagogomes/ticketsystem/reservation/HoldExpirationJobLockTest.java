package dev.thiagogomes.ticketsystem.reservation;

import dev.thiagogomes.ticketsystem.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Two instances firing the job at the same time: only one runs the body.
 */
@IntegrationTest
class HoldExpirationJobLockTest {

    private static final String LOCK_KEY = "job-lock:ticket-system:hold-expiration";

    @Autowired
    HoldExpirationJob job;
    @Autowired
    StringRedisTemplate redis;
    @MockitoSpyBean
    HoldService holdService;

    @Test
    void concurrentRunsExecuteTheJobOnce() throws Exception {
        doAnswer(invocation -> {
            Thread.sleep(300); // hold the lock while the other "instance" tries
            return invocation.callRealMethod();
        }).when(holdService).releaseExpired();
        redis.delete(LOCK_KEY); // the scheduler may already have run it at startup

        CompletableFuture<?>[] runs = new CompletableFuture<?>[2];
        for (int i = 0; i < runs.length; i++) {
            runs[i] = CompletableFuture.runAsync(job::releaseExpiredHolds);
        }
        CompletableFuture.allOf(runs).get();

        verify(holdService, times(1)).releaseExpired();
        assertThat(redis.hasKey(LOCK_KEY)).as("lock kept for the rest of the interval").isTrue();
    }
}
