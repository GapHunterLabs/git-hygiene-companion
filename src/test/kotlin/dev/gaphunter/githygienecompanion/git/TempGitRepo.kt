package dev.gaphunter.githygienecompanion.git

import java.io.File
import java.nio.file.Files

/**
 * A throwaway git repository for tests, created with fictional identities
 * and isolated from the machine's git configuration (no global or system
 * config: no user name/email, hooks or autocrlf leak in).
 */
class TempGitRepo private constructor(val dir: File) {

    fun write(relativePath: String, text: String): File {
        val file = File(dir, relativePath)
        file.parentFile.mkdirs()
        file.writeText(text)
        return file
    }

    fun commitAll(message: String, author: String = "Ada Lovelace", email: String = "ada@example.com") {
        git("add", "-A")
        git("commit", "-q", "-m", message, identity = author to email)
    }

    fun headCommit(): String = git("rev-parse", "HEAD").trim()

    fun delete() {
        // git marks object files read-only on Windows: make them writable first
        dir.walkBottomUp().forEach { it.setWritable(true) }
        dir.deleteRecursively()
    }

    private fun git(vararg args: String, identity: Pair<String, String>? = null): String {
        val builder = ProcessBuilder(listOf("git", "-C", dir.path) + args.toList()).redirectErrorStream(true)
        val env = builder.environment()
        env.keys.removeIf { it.startsWith("GIT_") }
        env["GIT_CONFIG_GLOBAL"] = File(dir, ".git/no-global-config").path
        env["GIT_CONFIG_NOSYSTEM"] = "1"
        if (identity != null) {
            env["GIT_AUTHOR_NAME"] = identity.first
            env["GIT_AUTHOR_EMAIL"] = identity.second
            env["GIT_COMMITTER_NAME"] = identity.first
            env["GIT_COMMITTER_EMAIL"] = identity.second
        }
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }

    companion object {
        fun create(): TempGitRepo {
            val repo = TempGitRepo(Files.createTempDirectory("git-hygiene-test").toFile())
            repo.git("init", "-q", "-b", "main")
            return repo
        }
    }
}
