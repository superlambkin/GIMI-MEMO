package com.gijimemo.document

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MarkdownGenerator @Inject constructor() {

    fun generate(transcript: String, file: File): File {
        file.writeText(transcript)
        return file
    }
}
