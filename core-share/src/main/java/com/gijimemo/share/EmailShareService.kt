package com.gijimemo.share

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
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

    companion object {
        private const val TAG = "EmailShareService"
        // 優先メーラー (Gmail を最優先で直起動できる)
        private val PREFERRED_MAIL_PACKAGES = listOf(
            "com.google.android.gm",       // Gmail
            "com.microsoft.office.outlook", // Outlook
            "com.yahoo.mobile.client.android.mail", // Yahoo Mail
            "ch.protonmail.android",       // ProtonMail
            "com.fsck.k9",                 // K-9 Mail
        )
    }

    fun buildIntent(
        attachments: List<File>,
        subject: String,
        body: String,
        recipient: String
    ): Intent {
        // 添付 MIME。"application/octet-stream" は Gmail でも添付として確実に扱われる。
        // "message/rfc822" は Gmail が「メール本文」として解釈し添付が破棄される問題があった。
        val attachmentMime = "application/octet-stream"

        // FileProvider URI 一覧を先に作る (ClipData 用にも使う)
        val uris = attachments.map { file ->
            FileProvider.getUriForFile(context, fileProviderAuthority, file)
        }

        return if (attachments.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = attachmentMime
                putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                putExtra(Intent.EXTRA_STREAM, uris.single())
                // ClipData で grant を確実に target アプリへ伝播させる。
                // createChooser 配下では Intent extras 経由の grant が伝播しない端末がある。
                clipData = ClipData.newUri(context.contentResolver, "attachments", uris.single())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = attachmentMime
                putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                // 複数添付: 先頭 URI で ClipData 作成、残りは addItem で追加
                val clip = ClipData(
                    ClipDescription("attachments", arrayOf(attachmentMime)),
                    ClipData.Item(uris.first())
                )
                uris.drop(1).forEach { uri -> clip.addItem(ClipData.Item(uri)) }
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /**
     * 添付なしの `mailto:` Intent を生成する (fallback 用)。
     * ACTION_SENDTO + mailto: はメーラーアプリのみが resolve するため、
     * 添付付き SEND が解決できない場合に少なくともメーラーは起動できる。
     */
    private fun buildMailtoIntent(
        subject: String,
        body: String,
        recipient: String
    ): Intent {
        val uri = Uri.parse(
            "mailto:$recipient?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"
        )
        return Intent(Intent.ACTION_SENDTO, uri)
    }

    /**
     * 既知メーラーアプリの中で端末にインストールされている最初のパッケージ名を返す。
     */
    private fun findInstalledMailPackage(): String? {
        val pm = context.packageManager
        return PREFERRED_MAIL_PACKAGES.firstOrNull { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun shareViaEmail(
        attachments: List<File>,
        subject: String,
        body: String,
        recipient: String
    ) {
        if (recipient.isBlank()) {
            Log.w(TAG, "shareViaEmail: recipient is blank")
            Toast.makeText(context, "受信者を選択してください", Toast.LENGTH_SHORT).show()
            return
        }

        val sendIntent = buildIntent(attachments, subject, body, recipient)
        val preferredPkg = findInstalledMailPackage()
        val pm = context.packageManager

        Log.d(TAG, "shareViaEmail: preferredPkg=$preferredPkg attachments=${attachments.size} recipient=$recipient")

        try {
            // 戦略 1: 既知メーラーが入っているなら setPackage で直起動 (To/添付が一番確実に渡る)
            if (preferredPkg != null) {
                val directIntent = Intent(sendIntent).apply {
                    setPackage(preferredPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (directIntent.resolveActivity(pm) != null) {
                    Log.d(TAG, "Launching $preferredPkg directly")
                    context.startActivity(directIntent)
                    return
                }
            }

            // 戦略 2: Chooser で全候補から選ばせる (setSelector は使わない — extras 落ちるため)
            if (sendIntent.resolveActivity(pm) != null) {
                val chooser = Intent.createChooser(sendIntent, "メーラーを選択").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                Log.d(TAG, "Launching chooser")
                context.startActivity(chooser)
                return
            }

            // 戦略 3: 最後の fallback - mailto: で添付なしメーラーだけ起動
            val mailto = buildMailtoIntent(subject, body, recipient)
            if (mailto.resolveActivity(pm) != null) {
                val mailtoChooser = Intent.createChooser(mailto, "メーラーを選択").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(mailtoChooser)
                Toast.makeText(
                    context,
                    "添付なしで開きました (メーラーが添付に対応していません)",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            Log.w(TAG, "shareViaEmail: メーラーアプリが見つかりません")
            Toast.makeText(
                context,
                "メーラーアプリが見つかりません。Gmail などをインストールしてください。",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "shareViaEmail failed: ${e.message}", e)
            Toast.makeText(
                context,
                "メール共有に失敗しました: ${e.message ?: e::class.java.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}