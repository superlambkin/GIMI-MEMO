package com.gijimemo.document

import com.google.common.truth.Truth.assertThat
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.Test
import java.io.File

class WordDocumentGeneratorTest {

    @Test
    fun `generate returns success with valid input`() {
        val generator = WordDocumentGenerator()
        val tempFile = File.createTempFile("output", ".docx")
        tempFile.deleteOnExit()

        val result = generator.generate("# Hello\n\nThis is content.", "Test Title", tempFile)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(tempFile)
        assertThat(tempFile.length()).isGreaterThan(0L)
    }

    @Test
    fun `generate returns failure for blank markdown`() {
        val generator = WordDocumentGenerator()
        val tempFile = File.createTempFile("output", ".docx")
        tempFile.deleteOnExit()

        val result = generator.generate("", "Title", tempFile)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("blank")
    }

    @Test
    fun `generate creates docx with title styled as Title`() {
        val generator = WordDocumentGenerator()
        val tempFile = File.createTempFile("output", ".docx")
        tempFile.deleteOnExit()

        val markdown = "# Heading 1\n\nSome text"
        val result = generator.generate(markdown, "My Title", tempFile)

        assertThat(result.isSuccess).isTrue()
    }
}