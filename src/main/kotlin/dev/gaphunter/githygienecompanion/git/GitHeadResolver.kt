package dev.gaphunter.githygienecompanion.git

import java.io.File

/** Thin wrapper over GitBlameRunner.currentHeadCommit -- exists as its own class so BlameCache's cache-key logic doesn't need to know about ProcessOutput/exit codes directly. */
object GitHeadResolver {
    fun resolve(repoDirectory: File): String? {
        val output = GitBlameRunner.currentHeadCommit(repoDirectory)
        if (output.exitCode != 0) return null
        return output.stdout.trim().takeIf { it.isNotEmpty() }
    }
}
