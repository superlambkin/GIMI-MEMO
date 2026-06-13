package com.gijimemo.document

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class TextGeneratorTest {

    @Test
    fun `generate removes heading markers`() {
        val generator = TextGenerator()
        val tempFile = File.createTempFile("output", ".txt")
        tempFile.deleteOnExit()

        val transcript = "## Section Title"
        generator.generate(transcript, tempFile)

        assertThat(tempFile.readText()).doesNotContain("#")
    }

    @Test
    fun `generate removes bold markers`() {
        val generator = TextGenerator()
        val tempFile = File.createTempFile("output", ".txt")
        tempFile.deleteOnExit()

        val transcript = "This is **bold** text"
        generator.generate(transcript, tempFile)

        assertThat(tempFile.readText()).isEqualTo("This is bold text")
    }

    @Test
    fun `generate removes code markers`() {
        val generator = TextGenerator()
        val tempFile = File.createTempFile("output", ".txt")
        tempFile.deleteOnExit()

        val transcript = "Use `code` here"
        generator.generate(transcript, tempFile)

        assertThat(tempFile.readText()).isEqualTo("Use code here")
    }

    @Test
    fun `generate converts list dash to bullet`() {
        val generator = TextGenerator()
        val tempFile = File.createTempFile("output", ".txt")
        tempFile.deleteOnExit()

        val transcript = "- List item"
        generator.generate(transcript, tempFile)

        assertThat(tempFile.readText()).isEqualTo("• List item")
    }

    @Test
    fun `generate converts checkbox to checkbox symbol`() {
        val generator = TextGenerator()
        val tempFile = File.createTempFile("output", ".txt")
        tempFile.deleteOnExit()

        val transcript = "- [ ] Unchecked\n- [x] Checked"
        generator.generate(transcript, tempFile)

        val content = tempFile.readText()
        assertThat(content).contains("☐ Unchecked")
        assertThat(content).contains("☐ Checked")
    }
}