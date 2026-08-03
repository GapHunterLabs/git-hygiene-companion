package dev.gaphunter.githygienecompanion.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Both fixtures are real `git blame --line-porcelain` output captured from
 * real repos (see fixture headers) -- never hand-written -- so the parser
 * is validated against git's actual format, not a guess at it.
 */
class GitBlameParserTest {

    private fun loadFixture(name: String): String {
        val path = Paths.get("src/test/resources/fixtures/$name")
        return Files.readString(path)
    }

    @Test
    fun `parses single-commit fixture with boundary marker`() {
        val output = loadFixture("real-blame-output.txt")
        val lines = GitBlameParser.parse(output)

        assertEquals(4, lines.size)
        lines.forEach { line ->
            assertEquals("8388c4b27a9926cab1820fe0bbd43b96bd9e2bc5", line.commitHash)
            assertEquals("Gap Hunter Labs", line.author)
            assertEquals("superkennydiaz@gmail.com", line.authorEmail)
            assertEquals("Cert Companion 0.1.0: X.509 certificate and keystore viewer", line.summary)
        }
        assertEquals("# Cert Companion", lines[0].content)
        assertEquals("", lines[1].content)
        assertEquals(1, lines[0].lineNumber)
        assertEquals(4, lines[3].lineNumber)
    }

    @Test
    fun `parses multi-commit multi-author fixture and reuses cached metadata for repeated commits`() {
        val output = loadFixture("demo-blame-output.txt")
        val lines = GitBlameParser.parse(output)

        // PaymentProcessor.java is 26 real lines (confirmed via `git blame --line-porcelain | grep -c author`).
        assertEquals(26, lines.size)

        val authors = lines.map { it.author }.toSet()
        assertEquals(setOf("Ada Lovelace", "Grace Hopper"), authors)

        // Line 1 (package declaration) belongs to the very first commit, Ada's initial add.
        val line1 = lines.first { it.lineNumber == 1 }
        assertEquals("Ada Lovelace", line1.author)
        assertEquals("ada@example.com", line1.authorEmail)
        assertEquals("Add PaymentProcessor with basic charge validation", line1.summary)

        // Line 8 (author Grace Hopper per real capture) came from her RefundHandler/max-charge commit --
        // confirms metadata reuse works when a commit's group is NOT the first occurrence in the file.
        val line8 = lines.first { it.lineNumber == 8 }
        assertEquals("Grace Hopper", line8.author)
        assertEquals("grace@example.com", line8.authorEmail)

        // Every line group after the first occurrence of a given commit must still resolve full,
        // non-empty metadata -- this is the exact behavior that would silently break if the
        // "reuse cached metadata" path in GitBlameParser were wrong.
        assertTrue(lines.all { it.author.isNotBlank() && it.authorEmail.isNotBlank() && it.summary.isNotBlank() })

        // Line numbers must be contiguous 1..26, in order -- no group skipped or duplicated.
        assertEquals((1..26).toList(), lines.map { it.lineNumber })
    }

    @Test
    fun `returns empty list for empty input`() {
        assertEquals(emptyList<GitBlameLine>(), GitBlameParser.parse(""))
    }
}
