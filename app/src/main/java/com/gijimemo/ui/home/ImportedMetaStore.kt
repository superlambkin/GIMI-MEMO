package com.gijimemo.ui.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v0.7.2: インポートメタ情報を Composable 間で共有する Singleton ストア。
 *
 * 背景: Compose Navigation の NavBackStackEntry スコープでは、
 * 同じ [HomeViewModel] を `hiltViewModel()` で取得しても、
 * HomeScreen と ImportReviewScreen で別インスタンスが生成される。
 * そのため StateFlow で共有できない。
 *
 * 解決策: アプリプロセス全体で 1 インスタンスの [ImportedMetaStore] を
 * Hilt の @Singleton で注入し、メタ情報を一元管理する。
 *
 * - [set]: HomeViewModel.importAudioFile() 完了時に呼び出す
 * - [clear]: ImportReviewScreen でキャンセル/文字起こし開始時に呼び出す
 */
@Singleton
class ImportedMetaStore @Inject constructor() {
    private val _meta = MutableStateFlow<ImportedAudioMeta?>(null)
    val meta: StateFlow<ImportedAudioMeta?> = _meta.asStateFlow()

    fun set(meta: ImportedAudioMeta) {
        _meta.value = meta
    }

    fun clear() {
        _meta.value = null
    }
}