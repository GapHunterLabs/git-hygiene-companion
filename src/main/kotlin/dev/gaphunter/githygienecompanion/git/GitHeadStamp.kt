package dev.gaphunter.githygienecompanion.git

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A cheap signature of "where HEAD points", so the HEAD commit cached for a
 * repository is re-resolved after a commit, checkout, reset, pull or
 * rebase.
 *
 * Fixes a real bug found while recording the product walkthrough
 * (2026-09-30): HEAD was resolved once per repository and never again --
 * only a change to a file's own timestamp triggered a recompute, and a
 * commit doesn't touch the committed file. A line edited, saved and then
 * committed kept showing "Not Committed Yet".
 *
 * The signature only stats files (no reads, no `git` process), because it
 * is checked from the editor's paint path:
 * - `.git/HEAD` (changes on checkout of another branch),
 * - `.git/logs/HEAD` (the reflog: appended on every HEAD move, including
 *   commits),
 * - `.git/packed-refs` (rewritten by fetch/gc),
 * - `.git/refs/heads` (a commit rewrites the branch's ref file there, which
 *   also covers repositories with the reflog turned off).
 * It is computed at most once per [MIN_INTERVAL_MS] per repository: the
 * painter asks once per visible line.
 *
 * Limitation, deliberate: a repository whose `.git` is a file (a linked
 * worktree or a submodule) gets a constant signature, i.e. the previous
 * behaviour (HEAD resolved once).
 */
object GitHeadStamp {
    const val MIN_INTERVAL_MS = 1_000L

    private data class Sample(val atNanos: Long, val stamp: Long)

    private val samples = ConcurrentHashMap<String, Sample>()

    fun of(repoDirectory: File, nowNanos: Long = System.nanoTime()): Long {
        val key = repoDirectory.path
        val previous = samples[key]
        if (previous != null && nowNanos - previous.atNanos < MIN_INTERVAL_MS * 1_000_000L) return previous.stamp
        val stamp = compute(repoDirectory)
        samples[key] = Sample(nowNanos, stamp)
        return stamp
    }

    /** Uncached signature; [of] is what the paint path uses. */
    fun compute(repoDirectory: File): Long {
        val gitDir = File(repoDirectory, ".git")
        if (!gitDir.isDirectory) return 0L
        var stamp = 17L
        for (path in listOf("HEAD", "logs/HEAD", "packed-refs", "refs/heads")) {
            stamp = stamp * 31 + File(gitDir, path).lastModified()
        }
        return stamp
    }

    fun invalidateAll() {
        samples.clear()
    }
}
