package dev.gaphunter.githygienecompanion.ui

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import dev.gaphunter.githygienecompanion.cache.BlameCache
import dev.gaphunter.githygienecompanion.cache.HeadCommitCache
import dev.gaphunter.githygienecompanion.git.GitBlameParser
import dev.gaphunter.githygienecompanion.git.GitBlameRunner
import dev.gaphunter.githygienecompanion.git.GitHeadResolver
import dev.gaphunter.githygienecompanion.git.GitHeadStamp
import java.io.File

/**
 * Task.Backgroundable wrapper around GitBlameRunner -- guarantees blame
 * computation NEVER runs on the EDT regardless of what calls it, the
 * same structural guarantee ReactNativeCommandRunner's OSProcessHandler
 * gives react-native-companion. GitBlameLinePainter triggers this and
 * returns immediately with whatever's already cached (possibly nothing);
 * this task fills the cache asynchronously and the painter picks it up
 * on its NEXT paint call, never blocking the current one.
 */
class GitBlameBackgroundTask(
    project: Project,
    private val repoDirectory: File,
    private val relativeFilePath: String,
    private val absoluteFilePath: String,
    private val onDone: () -> Unit,
) : Task.Backgroundable(project, "Computing git blame", false) {

    override fun run(indicator: ProgressIndicator) {
        // Stamp taken BEFORE resolving: if HEAD moves in between, the stored
        // stamp is already stale and the next paint resolves HEAD again.
        var headStamp = GitHeadStamp.refresh(repoDirectory)
        var headCommit = GitHeadResolver.resolve(repoDirectory) ?: return
        // A commit touches the stamped files in sequence (ref lock, reflog,
        // ref). Right after one, the stamp may have been taken mid-move with
        // HEAD still on the old commit -- and nothing repaints later to
        // notice (seen 2026-09-30: the old "Not Committed Yet" stayed on
        // screen after committing from the IDE). Only when the stamped files
        // changed in the last moment, wait for them to settle.
        for (attempt in 0 until SETTLE_ATTEMPTS) {
            if (System.currentTimeMillis() - GitHeadStamp.newestChangeMillis(repoDirectory) > RECENT_MS) break
            Thread.sleep(SETTLE_MS)
            indicator.checkCanceled()
            val again = GitHeadStamp.refresh(repoDirectory)
            if (again == headStamp) break
            headStamp = again
            headCommit = GitHeadResolver.resolve(repoDirectory) ?: return
        }
        // Populate HeadCommitCache here -- this is the ONLY place HEAD is
        // ever resolved. GitBlameLinePainter (running on the EDT) only
        // ever reads this cache, never resolves HEAD itself.
        HeadCommitCache.put(repoDirectory.path, headStamp, headCommit)
        val fileLastModified = File(absoluteFilePath).lastModified()

        // Re-check the cache inside the background task itself -- two
        // rapid paint calls for the same file could both have missed the
        // cache and scheduled a task before the first one finished; this
        // avoids running `git blame` twice for the same (headCommit,
        // fileLastModified) pair.
        if (BlameCache.get(absoluteFilePath, headCommit, fileLastModified) != null) {
            onDone()
            return
        }

        val output = GitBlameRunner.blame(repoDirectory, relativeFilePath)
        // A file git can't blame (new, not committed yet) gets an empty
        // result for this HEAD and timestamp: nothing to paint, no retry on
        // every repaint, and a retry as soon as either key changes (e.g. the
        // file is committed). Before 0.1.2 this path returned without
        // calling onDone, the file stayed "in flight" forever, and its blame
        // never appeared -- not even after committing it -- until a restart.
        val lines = if (output.exitCode == 0) GitBlameParser.parse(output.stdout) else emptyList()
        BlameCache.put(absoluteFilePath, headCommit, fileLastModified, lines)
        onDone()
    }

    private companion object {
        /** "HEAD moved a moment ago": the stamped files changed within this many milliseconds. */
        const val RECENT_MS = 2_000L
        const val SETTLE_MS = 300L
        const val SETTLE_ATTEMPTS = 5
    }
}
