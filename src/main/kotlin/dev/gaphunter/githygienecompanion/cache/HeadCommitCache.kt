package dev.gaphunter.githygienecompanion.cache

import java.util.concurrent.ConcurrentHashMap

/**
 * Fixes a real bug caught during manual smoke testing (runIde,
 * 2026-08-03): GitBlameLinePainter.getLineExtensions was calling
 * GitHeadResolver.resolve() -- which shells out to `git rev-parse HEAD`
 * -- directly on the EDT, on every repaint, before ever consulting
 * BlameCache. The platform's own EDT-guard (checkEdtAndReadAction)
 * caught this as "Synchronous execution on EDT", firing repeatedly (23
 * times in the same session) -- the exact class of freeze this plugin
 * exists to prevent, despite the class-level doc comment's claim that
 * getLineExtensions "reads ONLY from BlameCache".
 *
 * Populated EXCLUSIVELY from GitBlameBackgroundTask (never from the
 * EDT) -- getLineExtensions only ever reads from this map. A miss here
 * schedules the same background task that already resolves HEAD and
 * fills BlameCache, so no new background work is introduced -- this
 * cache just gives the EDT a safe, synchronous way to read what that
 * task already computed, instead of ever resolving it directly.
 */
object HeadCommitCache {
    private val cache = ConcurrentHashMap<String, String>()

    fun get(repoPath: String): String? = cache[repoPath]

    fun put(repoPath: String, headCommit: String) {
        cache[repoPath] = headCommit
    }
}
