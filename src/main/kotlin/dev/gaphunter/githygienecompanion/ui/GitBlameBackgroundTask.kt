package dev.gaphunter.githygienecompanion.ui

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import dev.gaphunter.githygienecompanion.cache.BlameCache
import dev.gaphunter.githygienecompanion.cache.HeadCommitCache
import dev.gaphunter.githygienecompanion.git.GitBlameParser
import dev.gaphunter.githygienecompanion.git.GitBlameRunner
import dev.gaphunter.githygienecompanion.git.GitHeadResolver
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
        val headCommit = GitHeadResolver.resolve(repoDirectory) ?: return
        // Populate HeadCommitCache here -- this is the ONLY place HEAD is
        // ever resolved. GitBlameLinePainter (running on the EDT) only
        // ever reads this cache, never resolves HEAD itself.
        HeadCommitCache.put(repoDirectory.path, headCommit)
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
        if (output.exitCode != 0) return
        val lines = GitBlameParser.parse(output.stdout)
        BlameCache.put(absoluteFilePath, headCommit, fileLastModified, lines)
        onDone()
    }
}
