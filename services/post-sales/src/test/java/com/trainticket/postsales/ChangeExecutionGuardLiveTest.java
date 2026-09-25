package com.trainticket.postsales;

import static org.junit.jupiter.api.Assertions.*;
import com.trainticket.postsales.application.ChangeExecutionGuard;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class ChangeExecutionGuardLiveTest {
    @Test
    void independentCasesDoNotShareAGlobalExecutionBudget() throws Exception {
        var guard = new ChangeExecutionGuard();
        int caseCount = 64;
        var acquired = new CountDownLatch(caseCount);
        var finish = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(caseCount)) {
            var calls = new ArrayList<Future<Boolean>>();
            for (int index = 0; index < caseCount; index++) {
                String id = "psc-case-" + index;
                calls.add(workers.submit(() -> guard.execute(id, () -> {
                    acquired.countDown();
                    try {
                        if (!finish.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("guard test timed out");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                    return true;
                }, () -> false)));
            }
            try {
                assertTrue(acquired.await(5, TimeUnit.SECONDS), "independent cases were throttled; missing=" + acquired.getCount());
            } finally {
                finish.countDown();
            }
            for (var call : calls) assertTrue(call.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void theSameCaseIsSingleFlightAndTheLockIsReleasedAfterFailure() throws Exception {
        var guard = new ChangeExecutionGuard();
        var acquired = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var first = workers.submit(() -> guard.execute("case-one", () -> {
                acquired.countDown();
                try {
                    if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("guard test timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                return true;
            }, () -> false));
            assertTrue(acquired.await(5, TimeUnit.SECONDS));
            assertFalse(guard.execute("psc-case-one", () -> true, () -> false));
            finish.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
        }
        assertThrows(IllegalStateException.class,
            () -> guard.execute("case-one", () -> { throw new IllegalStateException("guarded command failure"); }, () -> false));
        assertTrue(guard.execute("psc-case-one", () -> true, () -> false));
    }
}
