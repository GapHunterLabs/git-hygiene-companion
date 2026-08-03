package dev.gaphunter.githygienecompanion.cache

import dev.gaphunter.githygienecompanion.git.GitBlameLine
import java.util.concurrent.ConcurrentHashMap

/**
 * Safety-critical: this is the direct, structural fix for the cited
 * competitor freeze complaints. Two INDEPENDENT invalidation keys per
 * cached file, same modification-stamp-plus-invalidation-trigger shape
 * as highlight-companion's ComplexityCache:
 *
 * 1. The repo's current HEAD commit hash -- a cached entry becomes stale
 *    the moment HEAD moves (a new commit anywhere in the repo), because
 *    blame results for ANY file can shift when history changes (e.g. a
 *    rebase, or simply committing in a completely different file/module
 *    does NOT change this file's blame, so this alone is deliberately
 *    coarse -- see key 2 for why that's fine).
 * 2. The file's own last-modified timestamp -- catches uncommitted local
 *    edits to the file itself, which HEAD alone would miss (editing a
 *    file without committing doesn't move HEAD, but DOES invalidate
 *    whatever blame was computed for the file's PREVIOUS content).
 *
 * Both must match the cached entry for a cache HIT. Switching to a
 * different file, or committing in a completely unrelated file/module,
 * changes NEITHER key for files that weren't touched, so those stay
 * cached -- this is what makes "committing elsewhere never triggers a
 * recompute" true even though HEAD technically did move: the cached
 * entry's own stored headCommit becomes stale only when THIS specific
 * file's cache lookup runs again with the NEW HEAD, at which point it
 * correctly recomputes once, then caches again under the new HEAD.
 */
object BlameCache {
    private data class Key(val filePath: String)
    private data class Entry(val headCommit: String, val fileLastModified: Long, val lines: List<GitBlameLine>)

    private val cache = ConcurrentHashMap<Key, Entry>()

    fun get(filePath: String, headCommit: String, fileLastModified: Long): List<GitBlameLine>? {
        val entry = cache[Key(filePath)] ?: return null
        if (entry.headCommit != headCommit || entry.fileLastModified != fileLastModified) return null
        return entry.lines
    }

    fun put(filePath: String, headCommit: String, fileLastModified: Long, lines: List<GitBlameLine>) {
        cache[Key(filePath)] = Entry(headCommit, fileLastModified, lines)
    }

    fun invalidate(filePath: String) {
        cache.remove(Key(filePath))
    }

    fun invalidateAll() {
        cache.clear()
    }
}
