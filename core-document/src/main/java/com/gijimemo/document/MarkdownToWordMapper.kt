package com.gijimemo.document

import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFRun

object MarkdownToWordMapper {

    fun renderInto(document: XWPFDocument, markdown: String) {
        val lines = markdown.lines()
        var inCodeBlock = false
        val codeBlockContent = StringBuilder()

        for (line in lines) {
            when {
                line.startsWith("```") -> {
                    if (inCodeBlock) {
                        flushCodeBlock(document, codeBlockContent)
                        codeBlockContent.clear()
                        inCodeBlock = false
                    } else {
                        inCodeBlock = true
                    }
                }
                inCodeBlock -> {
                    if (codeBlockContent.isNotEmpty()) codeBlockContent.append("\n")
                    codeBlockContent.append(line)
                }
                line.startsWith("# ") -> {
                    addHeading(document, line.removePrefix("# "), "Heading1")
                }
                line.startsWith("## ") -> {
                    addHeading(document, line.removePrefix("## "), "Heading2")
                }
                line.startsWith("### ") -> {
                    addHeading(document, line.removePrefix("### "), "Heading3")
                }
                line.startsWith("> ") -> {
                    addBlockQuote(document, line.removePrefix("> "))
                }
                line.startsWith("- [ ] ") -> {
                    addCheckboxItem(document, line.removePrefix("- [ ] "), checked = false)
                }
                line.startsWith("- [x] ") || line.startsWith("- [X] ") -> {
                    val text = line.removePrefix("- [x] ").removePrefix("- [X] ")
                    addCheckboxItem(document, text, checked = true)
                }
                line.startsWith("- ") -> {
                    addBulletItem(document, line.removePrefix("- "))
                }
                line.matches(Regex("^\\d+\\.\\s+.*")) -> {
                    val match = Regex("^(\\d+)\\.\\s+(.*)").find(line)
                    if (match != null) {
                        addOrderedItem(document, match.groupValues[2])
                    }
                }
                line.isBlank() -> {
                    // skip blank lines
                }
                else -> {
                    addParagraphWithInlineStyles(document, line)
                }
            }
        }
    }

    private fun addHeading(document: XWPFDocument, text: String, style: String) {
        val paragraph = document.createParagraph()
        paragraph.style = style
        val run = paragraph.createRun()
        run.setText(stripAsterisks(text))
    }

    private fun addBlockQuote(document: XWPFDocument, text: String) {
        val paragraph = document.createParagraph()
        val run = paragraph.createRun()
        run.setText("\"${stripAsterisks(text)}\"")
        run.isItalic = true
    }

    private fun addCheckboxItem(document: XWPFDocument, text: String, checked: Boolean) {
        val checkbox = if (checked) "☑ " else "☐ "
        val paragraph = document.createParagraph()
        val run = paragraph.createRun()
        run.setText(checkbox + stripAsterisks(text))
    }

    private fun addBulletItem(document: XWPFDocument, text: String) {
        val paragraph = document.createParagraph()
        val run = paragraph.createRun()
        run.setText("• ${stripAsterisks(text)}")
    }

    private fun addOrderedItem(document: XWPFDocument, text: String) {
        val paragraph = document.createParagraph()
        val run = paragraph.createRun()
        run.setText(stripAsterisks(text))
    }

    private fun addParagraphWithInlineStyles(document: XWPFDocument, text: String) {
        val paragraph = document.createParagraph()
        processInlineStyles(paragraph, text)
    }

    private fun processInlineStyles(paragraph: XWPFParagraph, text: String) {
        var remaining = text

        while (remaining.isNotEmpty()) {
            val boldMatch = Regex("\\*\\*(.+?)\\*\\*").find(remaining)
            val codeMatch = Regex("`(.+?)`").find(remaining)

            val boldPos = boldMatch?.range?.first ?: Int.MAX_VALUE
            val codePos = codeMatch?.range?.first ?: Int.MAX_VALUE

            when {
                boldPos < codePos && boldPos != Int.MAX_VALUE -> {
                    if (boldMatch!!.range.first > 0) {
                        paragraph.createRun().setText(remaining.substring(0, boldMatch.range.first))
                    }
                    val boldRun = paragraph.createRun()
                    boldRun.setText(boldMatch.groupValues[1])
                    boldRun.isBold = true
                    remaining = remaining.substring(boldMatch.range.last + 1)
                }
                codePos < boldPos && codePos != Int.MAX_VALUE -> {
                    if (codeMatch!!.range.first > 0) {
                        paragraph.createRun().setText(remaining.substring(0, codeMatch.range.first))
                    }
                    val codeRun = paragraph.createRun()
                    codeRun.setText(codeMatch.groupValues[1])
                    codeRun.isCapitalized = true
                    remaining = remaining.substring(codeMatch.range.last + 1)
                }
                else -> {
                    paragraph.createRun().setText(stripAsterisks(remaining))
                    break
                }
            }
        }
    }

    /**
     * v0.9.1: Markdown の **bold** / *italic* マーカーを除去し、Word 文書に
     * アスタリスクがそのまま残らないようにする。
     * 一致する **...** は内容だけ残し、不一致の ** や単独 * も削除する。
     */
    private fun stripAsterisks(text: String): String =
        text
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace("**", "")
            .replace(Regex("\\*(.+?)\\*"), "$1")
            .replace("*", "")

    private fun flushCodeBlock(document: XWPFDocument, content: StringBuilder) {
        val paragraph = document.createParagraph()
        val run = paragraph.createRun()
        run.setText(content.toString())
    }
}