package dev.gaphunter.githygienecompanion.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@State(name = "GitHygieneCompanionSettings", storages = [Storage("gitHygieneCompanion.xml")])
class GitHygieneCompanionSettings : PersistentStateComponent<GitHygieneCompanionSettings.State> {

    class State {
        var blameEnabled: Boolean = true
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        // Keep the static, no-Project-required mirror in sync -- see
        // GitHygieneCompanionRuntimeSettings' own doc comment for why
        // EditorLinePainter needs this indirection.
        GitHygieneCompanionRuntimeSettings.updateFrom(state.blameEnabled)
    }

    var blameEnabled: Boolean
        get() = state.blameEnabled
        set(value) {
            state.blameEnabled = value
            GitHygieneCompanionRuntimeSettings.updateFrom(value)
        }

    companion object {
        fun getInstance(): GitHygieneCompanionSettings = service()
    }
}

/**
 * EditorLinePainter is instantiated by the platform's extension-point
 * machinery with no application/project context handed to its
 * constructor, and getLineExtensions itself is a hot, EDT-synchronous
 * path where resolving a project-level service on every single call
 * would be unnecessary overhead for a single boolean flag. This static
 * object mirrors the one setting that actually needs to be read from
 * that hot path; GitHygieneCompanionSettings (the real, persisted
 * service) is the single source of truth and keeps this in sync on
 * every load/change.
 */
object GitHygieneCompanionRuntimeSettings {
    @Volatile
    private var enabled: Boolean = true

    fun isBlameEnabled(): Boolean = enabled

    fun updateFrom(value: Boolean) {
        enabled = value
    }
}
