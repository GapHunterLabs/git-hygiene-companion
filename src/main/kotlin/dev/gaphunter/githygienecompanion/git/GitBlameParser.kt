package dev.gaphunter.githygienecompanion.git

/**
 * Parses real `git blame --line-porcelain` output -- format confirmed by
 * capturing actual output from a real repo in this workspace (see
 * src/test/resources/fixtures/real-blame-output.txt), not guessed from
 * documentation. Pure Kotlin, no process spawning here -- GitBlameRunner
 * owns the process, this class only parses whatever text it produced.
 *
 * Porcelain format per line-group: a header line
 * "<hash> <orig-line> <final-line> [<num-lines-in-group>]", followed by
 * metadata lines (author/author-mail/author-time/.../summary/boundary?/
 * previous?/filename), followed by exactly one content line starting
 * with a literal TAB character. A commit's metadata is only repeated in
 * full the FIRST time that commit appears in the output -- subsequent
 * line-groups from the same commit only get the header + a bare TAB
 * content line, per git's own porcelain format spec. This parser tracks
 * already-seen commits and reuses their cached metadata for exactly that
 * case.
 */
object GitBlameParser {

    private val HEADER_PATTERN = Regex("""^([0-9a-f]{40}) (\d+) (\d+)(?: \d+)?$""")

    private data class CommitMeta(var author: String = "", var authorMail: String = "", var authorTime: Long = 0L, var summary: String = "")

    fun parse(porcelainOutput: String): List<GitBlameLine> {
        val lines = porcelainOutput.split("\n")
        val results = mutableListOf<GitBlameLine>()
        val commitCache = mutableMapOf<String, CommitMeta>()

        var i = 0
        while (i < lines.size) {
            val headerMatch = HEADER_PATTERN.find(lines[i])
            if (headerMatch == null) {
                // Not a header line (e.g. a blank trailing line from the
                // split) -- skip and keep scanning rather than failing the
                // whole parse on one unexpected line.
                i++
                continue
            }
            val hash = headerMatch.groupValues[1]
            val finalLineNumber = headerMatch.groupValues[3].toInt()
            i++

            val meta = commitCache.getOrPut(hash) { CommitMeta() }
            // Consume metadata lines until we hit the content line (starts with a literal tab).
            while (i < lines.size && !lines[i].startsWith("\t")) {
                val line = lines[i]
                when {
                    line.startsWith("author ") -> meta.author = line.removePrefix("author ")
                    line.startsWith("author-mail ") -> meta.authorMail = line.removePrefix("author-mail ").trim('<', '>')
                    line.startsWith("author-time ") -> meta.authorTime = line.removePrefix("author-time ").toLongOrNull() ?: meta.authorTime
                    line.startsWith("summary ") -> meta.summary = line.removePrefix("summary ")
                    // committer-*/committer-tz/author-tz/boundary/previous/filename: not needed for v0.1's display, intentionally skipped.
                }
                i++
            }

            if (i >= lines.size) break // Truncated output -- no content line to consume.
            val content = lines[i].removePrefix("\t")
            i++

            results.add(GitBlameLine(hash, finalLineNumber, meta.author, meta.authorMail, meta.authorTime, meta.summary, content))
        }
        return results
    }
}
