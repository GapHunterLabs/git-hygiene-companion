package dev.gaphunter.githygienecompanion.ui

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.gaphunter.githygienecompanion.cache.BlameCache
import dev.gaphunter.githygienecompanion.cache.HeadCommitCache
import dev.gaphunter.githygienecompanion.git.GitBlameLine
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Regression test for two real bugs caught during manual runIde smoke
 * testing (2026-08-03), neither of which GitBlameRunnerNonBlockingTest
 * (written the night this plugin was built) could catch, because that
 * test exercised GitBlameRunner in isolation on a background thread and
 * never called the real getLineExtensions path:
 *
 * 1. getLineExtensions used to call GitHeadResolver.resolve() -- a real
 *    `git rev-parse HEAD` subprocess -- directly on the EDT, before ever
 *    checking a cache. The platform's own EDT-guard flagged it as
 *    "Synchronous execution on EDT", firing 23 times in a single session
 *    opening one file.
 * 2. Once that was fixed, GitBlameBackgroundTask's completion callback
 *    turned out to call PsiManager.findFile outside a read-action,
 *    which the platform's threading assertions also caught.
 *
 * Note on the cold-cache case: in this headless test environment,
 * Task.queue() runs the Task.Backgroundable synchronously within the
 * calling thread (no real IDE background-thread pool here), so a cold
 * cache's real elapsed time in this specific test harness includes the
 * actual `git` subprocess work and isn't a meaningful "did it block the
 * EDT" signal by itself -- GitBlameRunnerNonBlockingTest already covers
 * that concurrency property directly. What IS meaningful and asserted
 * here: getLineExtensions never throws a threading violation, and once
 * both caches are warm (the real post-background-task state), it reads
 * them synchronously and near-instantly, never re-touching git.
 */
class GitBlameLinePainterTest : BasePlatformTestCase() {

    private fun realDemoFile(): Pair<File, com.intellij.openapi.vfs.VirtualFile> {
        val repoDir = File("demo").absoluteFile
        check(File(repoDir, ".git").isDirectory) { "demo/ must be a real git repo -- got: $repoDir" }
        val realFile = File(repoDir, "src/payment/PaymentProcessor.java")
        check(realFile.isFile) { "expected real demo file at $realFile" }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(realFile)
        assertNotNull("Could not resolve demo file as a VirtualFile: $realFile", virtualFile)
        return repoDir to virtualFile!!
    }

    fun `test getLineExtensions on a cold cache never throws a threading violation and returns null`() {
        val (_, virtualFile) = realDemoFile()
        val painter = GitBlameLinePainter()

        // Must not throw (in particular: no ThreadingAssertions violation from
        // either resolving HEAD synchronously or touching PSI outside a
        // read-action in the background task's completion callback).
        val result = painter.getLineExtensions(project, virtualFile, 0)
        assertNull("Expected null on a cold cache (background task scheduled instead), got: $result", result)
    }

    fun `test getLineExtensions reads warm caches synchronously without touching git again`() {
        val (repoDir, virtualFile) = realDemoFile()
        val painter = GitBlameLinePainter()

        // Simulate exactly what GitBlameBackgroundTask would have already
        // written -- getLineExtensions must never need to recompute this
        // itself once both caches are warm.
        val fakeHeadCommit = "deadbeef00000000000000000000000000000000"
        val lastModified = File(virtualFile.path).lastModified()
        HeadCommitCache.put(repoDir.path, fakeHeadCommit)
        BlameCache.put(
            virtualFile.path,
            fakeHeadCommit,
            lastModified,
            listOf(GitBlameLine(fakeHeadCommit, 0, "Ada Lovelace", "ada@example.com", 1_780_000_000L, "test commit", "package com.acmecorp.payment;")),
        )

        val start = System.nanoTime()
        val result = painter.getLineExtensions(project, virtualFile, 0)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        assertNotNull("Expected real blame data from the warm cache, got null", result)
        assertTrue("Expected exactly one LineExtensionInfo, got: $result", result!!.size == 1)
        assertTrue(
            "Author name should appear in the rendered blame text: ${result.first().text}",
            result.first().text.contains("Ada Lovelace"),
        )
        assertTrue(
            "getLineExtensions took ${elapsedMs}ms reading warm caches -- a pure in-memory map lookup must be near-instant.",
            elapsedMs < 200,
        )
    }
}
