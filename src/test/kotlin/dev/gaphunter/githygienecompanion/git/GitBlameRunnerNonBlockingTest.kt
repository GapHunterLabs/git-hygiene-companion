package dev.gaphunter.githygienecompanion.git

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * This plugin's entire value proposition is "never blocks the editor" --
 * the cited competitor complaint is real IDE freezes tied to git
 * operations running on/blocking the wrong thread. This test makes that
 * an objective, automated pass/fail instead of a human eyeballing runIde:
 * a tight loop simulating the EDT increments a counter with a timestamp on
 * every iteration while GitBlameRunner.blame() runs concurrently on a real
 * background thread against the real demo repo. If the EDT-simulating loop
 * ever stalls for longer than the generous threshold below, GitBlameRunner
 * must have done something synchronous/blocking on a shared resource --
 * this test would catch that regression without anyone watching a screen.
 */
class GitBlameRunnerNonBlockingTest {

    private val demoRepo = File("demo").absoluteFile

    @Test
    fun `EDT-simulating loop never stalls while a real git blame runs on a background thread`() {
        check(File(demoRepo, ".git").isDirectory) { "demo/ must be a real git repo (see demo/README.md) -- got: $demoRepo" }

        val stopSignal = CountDownLatch(1)
        val maxObservedStallMs = AtomicLong(0)
        val iterations = AtomicLong(0)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val edtSimulator = executor.submit {
                var lastTick = System.nanoTime()
                while (stopSignal.count > 0) {
                    val now = System.nanoTime()
                    val stallMs = (now - lastTick) / 1_000_000
                    maxObservedStallMs.updateAndGet { current -> maxOf(current, stallMs) }
                    lastTick = now
                    iterations.incrementAndGet()
                    Thread.sleep(1)
                }
            }

            val blameFuture = executor.submit {
                repeat(5) {
                    GitBlameRunner.blame(demoRepo, "src/payment/PaymentProcessor.java")
                    GitBlameRunner.blame(demoRepo, "src/payment/RefundHandler.java")
                    GitBlameRunner.currentHeadCommit(demoRepo)
                }
            }

            blameFuture.get(30, TimeUnit.SECONDS)
            stopSignal.countDown()
            edtSimulator.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        assertTrue("EDT-simulating loop should have run many iterations concurrently with git blame", iterations.get() > 50)
        assertTrue(
            "EDT-simulating loop stalled for ${maxObservedStallMs.get()}ms -- GitBlameRunner must be blocking a shared resource",
            maxObservedStallMs.get() < 2000,
        )
    }
}
