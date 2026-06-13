package com.gijimemo.document

import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WordDocumentGenerator @Inject constructor() {

    fun generate(markdown: String, title: String, outputFile: File): Result<File> {
        return runCatching {
            require(markdown.isNotBlank()) { "Markdown content must not be blank" }

            XWPFDocument().use { document ->
                val titleParagraph = document.createParagraph()
                titleParagraph.style = "Title"
                val titleRun = titleParagraph.createRun()
                titleRun.setText(title)

                MarkdownToWordMapper.renderInto(document, markdown)

                FileOutputStream(outputFile).use { fos ->
                    document.write(fos)
                }
            }
            outputFile
        }
    }
}
