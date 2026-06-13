package com.gijimemo.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmailShareService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val fileProviderAuthority: String = "${context.packageName}.fileprovider"

    fun buildIntent(
        attachments: List<File>,
        subject: String,
        body: String,
        recipient: String
    ): Intent {
        return if (attachments.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                val uri = FileProvider.getUriForFile(
                    context,
                    fileProviderAuthority,
                    attachments.single()
                )
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                val uris = attachments.map { file ->
                    FileProvider.getUriForFile(
                        context,
                        fileProviderAuthority,
                        file
                    )
                }
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    fun shareViaEmail(
        attachments: List<File>,
        subject: String,
        body: String,
        recipient: String
    ) {
        val intent = buildIntent(attachments, subject, body, recipient)
        context.startActivity(Intent.createChooser(intent, null))
    }
}