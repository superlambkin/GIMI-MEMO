package com.gijimemo.document

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TextGenerator @Inject constructor() {

    fun generate(transcript: String, file: File): File {
        val text = transcript
            .removeMarkdownHeadings()
            .removeMarkdownBold()
            .removeMarkdownCode()
            .removeMarkdownCheckboxPrefix()
            .removeMarkdownListPrefix()
        file.writeText(text)
        return file
    }

    private fun String.removeMarkdownHeadings(): String =
        this.replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")

    private fun String.removeMarkdownBold(): String =
        this.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")

    private fun String.removeMarkdownCode(): String =
        this.replace(Regex("`(.+?)`"), "$1")

    private fun String.removeMarkdownListPrefix(): String =
        this.replace(Regex("^\\s*-\\s*", RegexOption.MULTILINE), "• ")

    private fun String.removeMarkdownCheckboxPrefix(): String =
        this.replace(Regex("^\\s*-\\s*\\[[ xX]\\]\\s*", RegexOption.MULTILINE), "☐ ")
}
