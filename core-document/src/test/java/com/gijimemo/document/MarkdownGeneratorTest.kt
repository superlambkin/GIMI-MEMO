package com.gijimemo.document

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class MarkdownGeneratorTest {

    @Test
    fun `generate writes transcript to file`() {
        val generator = MarkdownGenerator()
        val tempFile = File.createTempFile("output", ".md")
        tempFile.deleteOnExit()

        val transcript = "# Meeting Notes\n\n- Item one\n- Item two"
        val result = generator.generate(transcript, tempFile)

        assertThat(result.readText()).isEqualTo(transcript)
    }
}