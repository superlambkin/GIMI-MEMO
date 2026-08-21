package com.gijimemo.ui.processing

import com.gijimemo.llm.LlmException
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException

/**
 * チャンク転写リトライ判定の単体テスト。
 * 一時障害（429 / 5xx / ネットワーク断 / タイムアウト）のみリトライし、
 * 恒久障害（401 / 413）は即時失敗させる。
 */
class ProcessingViewModelRetryTest {

    @Test
    fun `isRetryable returns true for transient failures`() {
        assertThat(ProcessingViewModel.isRetryable(LlmException.RateLimited())).isTrue()
        assertThat(ProcessingViewModel.isRetryable(LlmException.ServerError(500, "boom"))).isTrue()
        assertThat(ProcessingViewModel.isRetryable(LlmException.ServerError(503, "unavailable"))).isTrue()
        assertThat(ProcessingViewModel.isRetryable(LlmException.NetworkError(IOException("conn reset")))).isTrue()
        assertThat(ProcessingViewModel.isRetryable(LlmException.Timeout())).isTrue()
        assertThat(ProcessingViewModel.isRetryable(IOException("socket closed"))).isTrue()
    }

    @Test
    fun `isRetryable returns false for permanent failures`() {
        assertThat(ProcessingViewModel.isRetryable(LlmException.InvalidApiKey())).isFalse()
        assertThat(ProcessingViewModel.isRetryable(LlmException.FileTooLarge())).isFalse()
        assertThat(ProcessingViewModel.isRetryable(RuntimeException("other"))).isFalse()
        assertThat(ProcessingViewModel.isRetryable(NullPointerException())).isFalse()
    }
}
