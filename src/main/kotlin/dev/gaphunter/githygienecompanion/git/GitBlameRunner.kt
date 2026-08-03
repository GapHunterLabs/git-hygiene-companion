package dev.gaphunter.githygienecompanion.git

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.util.SystemInfo
import java.io.File

/**
 * The entire reason this plugin exists: the real, cited competitor
 * complaint is IDE freezes ("Extreme slowdown on startup", "IDE
 * freezes... have to kill process") tied to a git-status/blame companion
 * blocking the EDT. Same off-EDT process pattern already proven safe in
 * this workspace (react-native-companion's ReactNativeCommandRunner,
 * built specifically to fix an analogous "severely impacting IDE
 * performance" complaint) -- GeneralCommandLine + a process handler that
 * reads output on background threads by construction, never
 * `Runtime.exec().waitFor()` on the calling thread.
 *
 * Uses CapturingProcessHandler (blocks the CALLING thread until the
 * process exits, but does NOT block the platform's own I/O threads) --
 * deliberately different from OSProcessHandler's streaming/listener shape
 * because blame output is needed as a single complete result to hand to
 * the parser, not streamed incrementally. The caller (GitBlameBackgroundTask)
 * is what guarantees this never runs on the EDT -- this class itself has
 * no opinion about which thread calls it, same as ReactNativeCommandRunner.
 */
object GitBlameRunner {

    private const val TIMEOUT_MS = 15_000

    fun blame(repoDirectory: File, relativeFilePath: String): ProcessOutput {
        val commandLine = GeneralCommandLine(gitExecutable(), "blame", "--line-porcelain", "--", relativeFilePath)
            .withWorkDirectory(repoDirectory)
            .withCharset(Charsets.UTF_8)
        val handler = CapturingProcessHandler(commandLine)
        return handler.runProcess(TIMEOUT_MS)
    }

    fun currentHeadCommit(repoDirectory: File): ProcessOutput {
        val commandLine = GeneralCommandLine(gitExecutable(), "rev-parse", "HEAD")
            .withWorkDirectory(repoDirectory)
            .withCharset(Charsets.UTF_8)
        val handler = CapturingProcessHandler(commandLine)
        return handler.runProcess(TIMEOUT_MS)
    }

    private fun gitExecutable(): String = if (SystemInfo.isWindows) "git.exe" else "git"
}
