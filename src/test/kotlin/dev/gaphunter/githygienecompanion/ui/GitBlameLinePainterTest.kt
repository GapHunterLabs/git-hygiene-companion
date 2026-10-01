package dev.gaphunter.githygienecompanion.ui

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.gaphunter.githygienecompanion.cache.BlameCache
import dev.gaphunter.githygienecompanion.cache.HeadCommitCache
import dev.gaphunter.githygienecompanion.git.GitBlameLine
import dev.gaphunter.githygienecompanion.git.GitHeadStamp
import dev.gaphunter.githygienecompanion.git.TempGitRepo
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

    // Both caches are process-wide singletons: without clearing them, a test
    // that warms them makes the cold-cache test fail depending on run order.
    override fun setUp() {
        super.setUp()
        BlameCache.invalidateAll()
        HeadCommitCache.invalidateAll()
        GitHeadStamp.invalidateAll()
    }

    override fun tearDown() {
        try {
            BlameCache.invalidateAll()
            HeadCommitCache.invalidateAll()
            GitHeadStamp.invalidateAll()
        } finally {
            super.tearDown()
        }
    }

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
        HeadCommitCache.put(repoDir.path, GitHeadStamp.compute(repoDir), fakeHeadCommit)
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

    private fun paintedText(painter: GitBlameLinePainter, file: VirtualFile, line: Int): String? {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val result = painter.getLineExtensions(project, file, line)
            if (result != null) return result.first().text
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(50)
        }
        return null
    }

    /**
     * Regression test for a real bug found while recording the product
     * walkthrough (2026-09-30), through the real paint path and a real
     * repository: HEAD was resolved once and never again, so a line edited,
     * saved and then committed kept showing "Not Committed Yet" (the commit
     * doesn't touch the file, so its timestamp -- the only other key --
     * didn't change either).
     */
    fun `test a commit refreshes the annotation of a just-committed line`() {
        val repo = TempGitRepo.create()
        try {
            val file = repo.write("src/charge.js", "const a = 1;\nconst b = 2;\n")
            repo.commitAll("first")
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!
            val painter = GitBlameLinePainter()
            assertTrue(paintedText(painter, virtualFile, 1).orEmpty().contains("Ada Lovelace"))

            Thread.sleep(50) // distinct timestamps on coarse file systems
            file.writeText("const a = 1;\nconst b = 3;\n")
            VfsUtil.markDirtyAndRefresh(false, false, false, virtualFile)
            assertTrue(paintedText(painter, virtualFile, 1).orEmpty().contains("Not Committed Yet"))

            Thread.sleep(50)
            repo.commitAll("second", author = "Grace Hopper", email = "grace@example.com")
            GitHeadStamp.invalidateAll() // the paint path re-stats at most once a second
            val afterCommit = paintedText(painter, virtualFile, 1).orEmpty()
            assertTrue("Expected the new commit's author after the commit, got: $afterCommit", afterCommit.contains("Grace Hopper"))
            assertTrue(paintedText(painter, virtualFile, 0).orEmpty().contains("Ada Lovelace"))
        } finally {
            repo.delete()
        }
    }

    /**
     * Regression test for a real bug found while recording the product
     * walkthrough (2026-09-30): the blame cache is keyed by the file's
     * on-disk timestamp, so a line inserted in the editor and not yet saved
     * left every annotation below it one line off -- each line showed the
     * author and date of the line above it, and the new, uncommitted line
     * showed an old commit's author. While the document has unsaved edits
     * the cached lines can't be matched to the editor's lines, so nothing is
     * painted until the file is saved (the save changes the timestamp and
     * the blame is recomputed for the saved content).
     */
    fun `test getLineExtensions paints nothing while the file has unsaved edits`() {
        val (repoDir, virtualFile) = realDemoFile()
        val painter = GitBlameLinePainter()

        val fakeHeadCommit = "deadbeef00000000000000000000000000000000"
        HeadCommitCache.put(repoDir.path, GitHeadStamp.compute(repoDir), fakeHeadCommit)
        BlameCache.put(
            virtualFile.path,
            fakeHeadCommit,
            File(virtualFile.path).lastModified(),
            listOf(
                GitBlameLine(fakeHeadCommit, 0, "Ada Lovelace", "ada@example.com", 1_780_000_000L, "first", "package com.acmecorp.payment;"),
                GitBlameLine(fakeHeadCommit, 1, "Grace Hopper", "grace@example.com", 1_781_000_000L, "second", ""),
            ),
        )
        assertNotNull("Saved file with warm caches should be annotated", painter.getLineExtensions(project, virtualFile, 1))

        val fileDocumentManager = FileDocumentManager.getInstance()
        val document = fileDocumentManager.getDocument(virtualFile)!!
        try {
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "// inserted, not saved\n") }
            assertTrue(fileDocumentManager.isFileModified(virtualFile))
            assertNull(
                "Line 1 is now the old line 0: painting the cached blame would show the wrong author",
                painter.getLineExtensions(project, virtualFile, 1),
            )
            assertNull("The inserted line is not committed: no author from the cache", painter.getLineExtensions(project, virtualFile, 0))
        } finally {
            WriteCommandAction.runWriteCommandAction(project) { fileDocumentManager.reloadFromDisk(document) }
        }
        assertFalse(fileDocumentManager.isFileModified(virtualFile))
        assertNotNull("Annotations come back once the edits are gone", painter.getLineExtensions(project, virtualFile, 1))
    }
}
