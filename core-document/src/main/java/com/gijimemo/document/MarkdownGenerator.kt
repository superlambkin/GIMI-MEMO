package com.gijimemo.document

import java.io.File

class MarkdownGenerator {

    fun generate(transcript: String, file: File): File {
        file.writeText(transcript)
        return file
    }
}