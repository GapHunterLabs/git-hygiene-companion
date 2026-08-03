package dev.gaphunter.githygienecompanion.cache

import dev.gaphunter.githygienecompanion.git.GitBlameLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Safety-critical (see BlameCache's own doc comment): a stale blame result
 * shown silently after a real commit would be a credible, hard-to-notice
 * bug. Each of the two independent invalidation keys is exercised on its
 * own, isolating the other, so a regression in either key's check fails a
 * specific test instead of being masked by the other key also changing.
 */
class BlameCacheTest {

    private val sampleLines = listOf(
        GitBlameLine("abc123", 1, "Ada Lovelace", "ada@example.com", 1_780_000_000L, "Initial commit", "package com.example;"),
    )

    @Before
    fun clearCacheBetweenTests() {
        BlameCache.invalidateAll()
    }

    @Test
    fun `cache hit when both keys match`() {
        BlameCache.put("src/Foo.java", headCommit = "head1", fileLastModified = 1000L, lines = sampleLines)

        val result = BlameCache.get("src/Foo.java", headCommit = "head1", fileLastModified = 1000L)

        assertEquals(sampleLines, result)
    }

    @Test
    fun `cache miss when HEAD commit changed but file untouched`() {
        BlameCache.put("src/Foo.java", headCommit = "head1", fileLastModified = 1000L, lines = sampleLines)

        val result = BlameCache.get("src/Foo.java", headCommit = "head2", fileLastModified = 1000L)

        assertNull("A commit elsewhere moving HEAD must invalidate this file's cached blame", result)
    }

    @Test
    fun `cache miss when file modified but HEAD unchanged`() {
        BlameCache.put("src/Foo.java", headCommit = "head1", fileLastModified = 1000L, lines = sampleLines)

        val result = BlameCache.get("src/Foo.java", headCommit = "head1", fileLastModified = 2000L)

        assertNull("An uncommitted local edit to the file must invalidate its cached blame even though HEAD didn't move", result)
    }

    @Test
    fun `cache miss when both keys changed`() {
        BlameCache.put("src/Foo.java", headCommit = "head1", fileLastModified = 1000L, lines = sampleLines)

        val result = BlameCache.get("src/Foo.java", headCommit = "head2", fileLastModified = 2000L)

        assertNull(result)
    }

    @Test
    fun `cache miss for a file that was never cached`() {
        assertNull(BlameCache.get("src/NeverCached.java", headCommit = "head1", fileLastModified = 1000L))
    }

    @Test
    fun `unrelated file stays cached after a different file is committed (HEAD moves) -- the core competitor-freeze fix`() {
        BlameCache.put("src/Untouched.java", headCommit = "head1", fileLastModified = 1000L, lines = sampleLines)
        BlameCache.put("src/OtherModule.java", headCommit = "head1", fileLastModified = 500L, lines = sampleLines)

        // Simulate: someone commits in OtherModule.java -- HEAD moves for the whole repo,
        // but Untouched.java's own cache lookup is still queried with the OLD headCommit
        // the caller last observed for it (i.e. nothing re-read Untouched.java yet).
        val stillCached = BlameCache.get("src/Untouched.java", headCommit = "head1", fileLastModified = 1000L)

        assertEquals(sampleLines, stillCached)
    }

    @Test
    fun `invalidate removes only the targeted file`() {
        BlameCache.put("src/A.java", headCommit = "head1", fileLastModified = 1000L, lines = sampleLines)
        BlameCache.put("src/B.java", headCommit = "head1", fileLastModified = 1000L, lines = sampleLines)

        BlameCache.invalidate("src/A.java")

        assertNull(BlameCache.get("src/A.java", "head1", 1000L))
        assertEquals(sampleLines, BlameCache.get("src/B.java", "head1", 1000L))
    }

    @Test
    fun `invalidateAll clears every entry`() {
        BlameCache.put("src/A.java", headCommit = "head1", fileLastModified = 1000L, lines = sampleLines)
        BlameCache.put("src/B.java", headCommit = "head1", fileLastModified = 1000L, lines = sampleLines)

        BlameCache.invalidateAll()

        assertNull(BlameCache.get("src/A.java", "head1", 1000L))
        assertNull(BlameCache.get("src/B.java", "head1", 1000L))
    }

    @Test
    fun `put overwrites a previous entry for the same file`() {
        BlameCache.put("src/Foo.java", headCommit = "head1", fileLastModified = 1000L, lines = sampleLines)
        val newLines = listOf(
            GitBlameLine("def456", 1, "Grace Hopper", "grace@example.com", 1_780_100_000L, "Later commit", "package com.example;"),
        )

        BlameCache.put("src/Foo.java", headCommit = "head2", fileLastModified = 2000L, lines = newLines)

        assertEquals(newLines, BlameCache.get("src/Foo.java", "head2", 2000L))
        assertNull("stale key combination from before the overwrite must not hit", BlameCache.get("src/Foo.java", "head1", 1000L))
    }
}
