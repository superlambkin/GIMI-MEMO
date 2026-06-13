package com.gijimemo.llm

sealed class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 401 - API Key 无效 */
    class InvalidApiKey : LlmException("API Key 无效，请到设置页检查")

    /** 413 - 文件过大 */
    class FileTooLarge : LlmException("文件过大，请调小切片阈值或缩短录音")

    /** 429 - 限流 */
    class RateLimited : LlmException("请求过于频繁，请稍后重试")

    /** 网络错（可重试） */
    class NetworkError(cause: Throwable) : LlmException("网络问题: ${cause.message}", cause)

    /** 超时 */
    class Timeout : LlmException("LLM 响应超时")

    /** 未知 */
    class Unknown(cause: Throwable) : LlmException("LLM 调用失败: ${cause.message}", cause)
}
