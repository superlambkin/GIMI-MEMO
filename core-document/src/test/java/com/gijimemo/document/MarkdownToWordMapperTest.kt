package com.gijimemo.document

import com.google.common.truth.Truth.assertThat
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.Test

class MarkdownToWordMapperTest {

    @Test
    fun `renders heading1`() {
        val document = XWPFDocument()
        MarkdownToWordMapper.renderInto(document, "# Hello World")
        val paragraphs = document.paragraphs
        assertThat(paragraphs).hasSize(1)
        assertThat(paragraphs[0].style).isEqualTo("Heading1")
        assertThat(paragraphs[0].text).isEqualTo("Hello World")
    }

    @Test
    fun `renders heading2`() {
        val document = XWPFDocument()
        MarkdownToWordMapper.renderInto(document, "## Section Two")
        val paragraphs = document.paragraphs
        assertThat(paragraphs).hasSize(1)
        assertThat(paragraphs[0].style).isEqualTo("Heading2")
    }

    @Test
    fun `renders heading3`() {
        val document = XWPFDocument()
        MarkdownToWordMapper.renderInto(document, "### Sub Section")
        val paragraphs = document.paragraphs
        assertThat(paragraphs[0].style).isEqualTo("Heading3")
    }

    @Test
    fun `renders checkbox unchecked`() {
        val document = XWPFDocument()
        MarkdownToWordMapper.renderInto(document, "- [ ] Task item")
        val text = document.paragraphs[0].text
        assertThat(text).startsWith("☐")
    }

    @Test
    fun `renders checkbox checked`() {
        val document = XWPFDocument()
        MarkdownToWordMapper.renderInto(document, "- [x] Done item")
        val text = document.paragraphs[0].text
        assertThat(text).startsWith("☑")
    }

    @Test
    fun `renders bullet list`() {
        val document = XWPFDocument()
        MarkdownToWordMapper.renderInto(document, "- Bullet point")
        val text = document.paragraphs[0].text
        assertThat(text).startsWith("•")
    }

    @Test
    fun `renders ordered list`() {
        val document = XWPFDocument()
        MarkdownToWordMapper.renderInto(document, "1. First item")
        assertThat(document.paragraphs[0].text).isEqualTo("First item")
    }

    @Test
    fun `skips blank lines`() {
        val document = XWPFDocument()
        MarkdownToWordMapper.renderInto(document, "Line 1\n\n\nLine 2")
        assertThat(document.paragraphs).hasSize(2)
    }

    @Test
    fun `renders blockquote`() {
        val document = XWPFDocument()
        MarkdownToWordMapper.renderInto(document, "> Quoted text")
        val text = document.paragraphs[0].text
        assertThat(text).contains("Quoted text")
    }
}