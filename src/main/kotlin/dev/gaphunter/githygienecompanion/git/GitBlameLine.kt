package dev.gaphunter.githygienecompanion.git

data class GitBlameLine(
    val commitHash: String,
    val lineNumber: Int,
    val author: String,
    val authorEmail: String,
    val authorTimeEpochSeconds: Long,
    val summary: String,
    val content: String,
)
