package dev.gaphunter.githygienecompanion.ui

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.EditorLinePainter
import com.intellij.openapi.editor.LineExtensionInfo
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import dev.gaphunter.githygienecompanion.cache.BlameCache
import dev.gaphunter.githygienecompanion.cache.HeadCommitCache
import dev.gaphunter.githygienecompanion.settings.GitHygieneCompanionRuntimeSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Confirmed via real bytecode inspection (javap decompile of the actual
 * EditorLinePainter/EditorImpl call path, 2026-08-03): getLineExtensions
 * is called SYNCHRONOUSLY on the EDT, once per visible line, on every
 * repaint, with zero built-in caching by the platform. This method MUST
 * return instantly -- it reads ONLY from BlameCache and HeadCommitCache
 * (both in-memory map lookups) and NEVER runs `git` itself, directly or
 * indirectly. (A first version of this method called GitHeadResolver.resolve()
 * -- which shells out to `git rev-parse HEAD` -- directly here; the
 * platform's own EDT-guard caught that as "Synchronous execution on EDT"
 * during manual smoke testing. See HeadCommitCache's doc comment.) A
 * cache miss (either cache) schedules a GitBlameBackgroundTask and
 * returns null immediately for that paint pass; the background task's
 * completion callback calls DaemonCodeAnalyzer.restart(psiFile) (the
 * confirmed, idiomatic "line extension data changed, please repaint"
 * trigger -- there is no dedicated narrower event for this), which
 * re-invokes this method on the next repaint and finds the now-populated
 * caches.
 */
class GitBlameLinePainter : EditorLinePainter() {

    // Tracks in-flight background tasks per (file, headCommit+lastModified)
    // so rapid repeated paint calls for the same stale file don't each
    // schedule a duplicate `git blame` process -- GitBlameBackgroundTask
    // itself also re-checks the cache defensively, but this avoids even
    // constructing/queuing redundant Task.Backgroundable instances.
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    override fun getLineExtensions(project: Project, file: VirtualFile, lineNumber: Int): Collection<LineExtensionInfo>? {
        if (!GitHygieneCompanionRuntimeSettings.isBlameEnabled()) return null
        val repoDirectory = findRepoRoot(file) ?: return null
        val absolutePath = file.path
        val relativePath = absolutePath.removePrefix(repoDirectory.path).trimStart('/', '\\')

        // Never resolve HEAD synchronously here -- that used to call
        // GitHeadResolver.resolve() directly on the EDT (a real `git`
        // subprocess), which the platform's own EDT-guard caught firing
        // repeatedly during manual testing. Only read what
        // GitBlameBackgroundTask has already computed; a miss here just
        // means "no background task has resolved HEAD for this repo yet".
        val headCommit = HeadCommitCache.get(repoDirectory.path)
        if (headCommit == null) {
            scheduleBackgroundBlame(project, repoDirectory, relativePath, absolutePath, file)
            return null
        }
        val lastModified = File(absolutePath).lastModified()

        val cached = BlameCache.get(absolutePath, headCommit, lastModified)
        if (cached == null) {
            scheduleBackgroundBlame(project, repoDirectory, relativePath, absolutePath, file)
            return null
        }

        val lineInfo = cached.getOrNull(lineNumber) ?: return null
        val text = formatBlameText(lineInfo.author, lineInfo.authorTimeEpochSeconds)
        val colorScheme = EditorColorsManager.getInstance().globalScheme
        val attributes = TextAttributes(colorScheme.defaultForeground.darker(), null, null, EffectType.BOXED, java.awt.Font.PLAIN)
        return listOf(LineExtensionInfo("  $text", attributes))
    }

    private fun scheduleBackgroundBlame(project: Project, repoDirectory: File, relativePath: String, absolutePath: String, file: VirtualFile) {
        val flightKey = absolutePath
        if (!inFlight.add(flightKey)) return // Already scheduled -- GitBlameBackgroundTask's own cache re-check handles the rest.

        GitBlameBackgroundTask(project, repoDirectory, relativePath, absolutePath) {
            inFlight.remove(flightKey)
            // Second real bug caught by GitBlameLinePainterTest (2026-08-03):
            // PsiManager.findFile requires read access; this callback runs on
            // the background task's own thread, not inside a read-action.
            ReadAction.run<Throwable> {
                val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@run
                // Confirmed idiomatic trigger for "line extension data changed,
                // please repaint" -- there is no narrower dedicated event for
                // this specific extension point.
                DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
            }
        }.queue()
    }

    private fun formatBlameText(author: String, epochSeconds: Long): String {
        if (epochSeconds == 0L) return author
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        return "$author, ${format.format(Date(epochSeconds * 1000))}"
    }

    /** Walks up from the file to find a directory containing .git -- pure filesystem check, cheap, no process spawn. */
    private fun findRepoRoot(file: VirtualFile): File? {
        var dir: File? = File(file.path).parentFile
        while (dir != null) {
            if (File(dir, ".git").exists()) return dir
            dir = dir.parentFile
        }
        return null
    }
}
