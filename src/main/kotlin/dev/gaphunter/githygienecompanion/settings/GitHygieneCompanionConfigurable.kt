package dev.gaphunter.githygienecompanion.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

class GitHygieneCompanionConfigurable : Configurable {

    private val blameCheckbox = JBCheckBox("Show inline git blame")

    override fun getDisplayName(): String = "Git Hygiene Companion"

    override fun createComponent(): JComponent {
        blameCheckbox.isSelected = GitHygieneCompanionSettings.getInstance().blameEnabled
        return JPanel().apply { add(blameCheckbox) }
    }

    override fun isModified(): Boolean = blameCheckbox.isSelected != GitHygieneCompanionSettings.getInstance().blameEnabled

    override fun apply() {
        GitHygieneCompanionSettings.getInstance().blameEnabled = blameCheckbox.isSelected
    }

    override fun reset() {
        blameCheckbox.isSelected = GitHygieneCompanionSettings.getInstance().blameEnabled
    }
}
