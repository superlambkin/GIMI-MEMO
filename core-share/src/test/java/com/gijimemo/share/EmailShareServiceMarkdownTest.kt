package com.gijimemo.share

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * v0.9.2: 共有テキストの Markdown 記法除去（stripMarkdown）の単体テスト。
 */
class EmailShareServiceMarkdownTest {

    @Test
    fun `removes heading markers`() {
        val result = stripMarkdown("# 会議概要\n\n## 決定事項\n\n### 1. 設計内容\n")
        assertThat(result).isEqualTo("会議概要\n\n決定事項\n\n1. 設計内容")
    }

    @Test
    fun `removes bold and italic markers`() {
        val result = stripMarkdown("**日時**: 2026年8月22日\n*斜体* と ***強調***\n")
        assertThat(result).isEqualTo("日時: 2026年8月22日\n斜体 と 強調")
    }

    @Test
    fun `removes bullet and checkbox markers`() {
        val result = stripMarkdown(
            "- 箇条書き1\n- [ ] 未完了タスク\n- [x] 完了タスク\n* 別リスト\n"
        )
        assertThat(result).isEqualTo("箇条書き1\n未完了タスク\n完了タスク\n別リスト")
    }

    @Test
    fun `keeps ordered list numbers`() {
        val result = stripMarkdown("1. 施工業者の選定\n2. 住民への協力依頼\n")
        assertThat(result).isEqualTo("1. 施工業者の選定\n2. 住民への協力依頼")
    }

    @Test
    fun `removes horizontal rules links and blockquote`() {
        val result = stripMarkdown("本文\n\n---\n\n> 引用文\n\n[リンク](https://example.com)\n")
        assertThat(result).isEqualTo("本文\n\n引用文\n\nリンク")
    }

    @Test
    fun `removes code fences`() {
        val result = stripMarkdown("前置き\n```kotlin\nval x = 1\n```\n後書き\n")
        assertThat(result).isEqualTo("前置き\n\n後書き")
    }
}
