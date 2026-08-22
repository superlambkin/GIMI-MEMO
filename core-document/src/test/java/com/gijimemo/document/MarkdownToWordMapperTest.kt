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

    // ─── v0.9.1: Word 文書に ** が残らない ──────────────

    @Test
    fun `removes bold markers from paragraph`() {
        val document = XWPFDocument()
        MarkdownToWordMapper.renderInto(document, "これは **重要** です")
        assertThat(document.paragraphs[0].text).isEqualTo("これは 重要 です")
        assertThat(document.paragraphs[0].text).doesNotContain("*")
    }

    @Test
    fun `removes unmatched bold markers`() {
        val document = XWPFDocument()
        MarkdownToWordMapper.renderInto(document, "中途半端な **マーカー")
        assertThat(document.paragraphs[0].text).doesNotContain("*")
    }

    @Test
    fun `removes bold markers from heading and bullet`() {
        val document = XWPFDocument()
        MarkdownToWordMapper.renderInto(document, "# **見出し**\n- **箇条書き**")
        assertThat(document.paragraphs[0].text).isEqualTo("見出し")
        assertThat(document.paragraphs[1].text).isEqualTo("• 箇条書き")
        document.paragraphs.forEach { assertThat(it.text).doesNotContain("*") }
    }
}