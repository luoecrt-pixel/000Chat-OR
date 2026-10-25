package com.example.openaichat

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DestroyedActivityCallbackCleanupTest {
    @Test fun databaseCloseWaitsForInterruptedCallbackAndNeverBlocksDestroyThread() {
        val executor = Executors.newSingleThreadExecutor()
        val callbackEntered = CountDownLatch(1)
        val destroyInterruptObserved = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val resourceClosed = CountDownLatch(1)
        val closedWhileRunning = AtomicBoolean(false)
        executor.execute {
            callbackEntered.countDown()
            while (releaseCallback.count > 0L) {
                try { releaseCallback.await() }
                catch (_: InterruptedException) { destroyInterruptObserved.countDown() }
            }
            closedWhileRunning.set(resourceClosed.count == 0L)
        }
        assertTrue(callbackEntered.await(5L, TimeUnit.SECONDS))
        executor.shutdownNow()
        val closer = ExecutorResourceCloser.closeAfterTermination(executor) { resourceClosed.countDown() }
        try {
            assertTrue(destroyInterruptObserved.await(5L, TimeUnit.SECONDS))
            assertFalse(resourceClosed.await(50L, TimeUnit.MILLISECONDS))
            closer.interrupt()
            assertFalse(resourceClosed.await(50L, TimeUnit.MILLISECONDS))
            releaseCallback.countDown()
            assertTrue(resourceClosed.await(5L, TimeUnit.SECONDS))
            assertFalse(closedWhileRunning.get())
        } finally {
            releaseCallback.countDown()
            closer.join(5_000L)
        }
    }
}
