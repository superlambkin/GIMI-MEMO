package com.gijimemo.ui.import_review

import androidx.lifecycle.ViewModel
import com.gijimemo.ui.home.ImportedAudioMeta
import com.gijimemo.ui.home.ImportedMetaStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * v0.7.2: Singleton [ImportedMetaStore] を Composable 側から
 * `hiltViewModel()` 経由でアクセスするための薄いラッパー。
 *
 * Singleton を直接取得する手段 (@EntryPoint 等) もあるが、
 * Composable では hiltViewModel() を使うのが標準的なので、
 * ダミー ViewModel で StateFlow を公開する。
 */
@HiltViewModel
class ImportedMetaStoreViewModel @Inject constructor(
    private val store: ImportedMetaStore
) : ViewModel() {
    val meta: StateFlow<ImportedAudioMeta?> = store.meta
    fun clear() = store.clear()
}