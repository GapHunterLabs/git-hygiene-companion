package dev.gaphunter.githygienecompanion.git

import dev.gaphunter.githygienecompanion.cache.HeadCommitCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Regression test for a real bug found while recording the product
 * walkthrough (2026-09-30): HEAD was resolved once per repository and never
 * again, so after a commit the just-committed lines kept showing "Not
 * Committed Yet". The cached HEAD is now tied to a stamp of the files that
 * change whenever HEAD moves.
 */
class GitHeadStampTest {

    private lateinit var repo: TempGitRepo

    @Before
    fun setUp() {
        repo = TempGitRepo.create()
        repo.write("src/charge.js", "const a = 1;\n")
        repo.commitAll("first")
        HeadCommitCache.invalidateAll()
        GitHeadStamp.invalidateAll()
    }

    @After
    fun tearDown() {
        HeadCommitCache.invalidateAll()
        GitHeadStamp.invalidateAll()
        repo.delete()
    }

    @Test
    fun `a commit changes the stamp and the cached HEAD stops matching`() {
        val stampBefore = GitHeadStamp.compute(repo.dir)
        HeadCommitCache.put(repo.dir.path, stampBefore, repo.headCommit())
        assertEquals(repo.headCommit(), HeadCommitCache.get(repo.dir.path, stampBefore))

        Thread.sleep(50) // distinct timestamps on coarse file systems
        repo.write("README.md", "# billing\n") // the annotated file itself is not touched
        repo.commitAll("second", author = "Grace Hopper", email = "grace@example.com")

        val stampAfter = GitHeadStamp.compute(repo.dir)
        assertNotEquals("A commit must change the HEAD stamp", stampBefore, stampAfter)
        assertNull("The HEAD cached before the commit must not be served after it", HeadCommitCache.get(repo.dir.path, stampAfter))
    }

    @Test
    fun `the stamp is stable while HEAD does not move`() {
        repo.write("src/charge.js", "const a = 2;\n") // an uncommitted edit doesn't move HEAD
        assertEquals(GitHeadStamp.compute(repo.dir), GitHeadStamp.compute(repo.dir))
    }

    @Test
    fun `refresh updates the paint-path sample so the repaint it triggers sees the same stamp`() {
        val t0 = 1_000_000_000L
        val before = GitHeadStamp.of(repo.dir, nowNanos = t0)
        Thread.sleep(50)
        repo.write("README.md", "# billing\n")
        repo.commitAll("second")
        val refreshed = GitHeadStamp.refresh(repo.dir, nowNanos = t0 + 1_000L)
        assertNotEquals(before, refreshed)
        assertEquals("Within the interval the paint path now reads the refreshed stamp", refreshed, GitHeadStamp.of(repo.dir, nowNanos = t0 + 2_000L))
    }

    @Test
    fun `newest change is recent right after a commit`() {
        repo.write("README.md", "# billing\n")
        repo.commitAll("second")
        val ageMs = System.currentTimeMillis() - GitHeadStamp.newestChangeMillis(repo.dir)
        assert(ageMs in 0..5_000) { "Stamped files should have just changed, age ${ageMs}ms" }
    }

    @Test
    fun `the paint-path stamp is reused within the interval and refreshed after it`() {
        val t0 = 1_000_000_000L
        val first = GitHeadStamp.of(repo.dir, nowNanos = t0)
        Thread.sleep(50)
        repo.write("README.md", "# billing\n")
        repo.commitAll("second")
        val withinInterval = GitHeadStamp.of(repo.dir, nowNanos = t0 + 100_000_000L)
        val afterInterval = GitHeadStamp.of(repo.dir, nowNanos = t0 + GitHeadStamp.MIN_INTERVAL_MS * 1_000_000L)
        assertEquals("Within the interval the cached sample is reused (no stats)", first, withinInterval)
        assertNotEquals("After the interval the commit is seen", first, afterInterval)
    }
}
