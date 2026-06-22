/*
 * JNI bridge for whisper.cpp on Android.
 * Based on the official whisper.cpp Android example.
 *
 * JNI functions map to Kotlin: com.gijimemo.whisper.WhisperJni
 */

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdio.h>
#include <sys/resource.h>
#include "whisper.h"
#include "ggml.h"

#define TAG "WhisperJNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─── setThreadPriority ──────────────────────────────────────────────────────────────────────────
// v0.7.4: バックグラウンドスレッドを含む全スレッドに高い優先度を設定し、
// ビッグコアへの割り当てを促進する。SCHED_BATCH はバッチ計算向け。
static void set_high_priority(void) {
    // nice 値を下げて優先度を上げる (-20 = 最高, 19 = 最低)
    setpriority(PRIO_PROCESS, 0, -10);
    // SCHED_FIFO / SCHED_RR は Android で制限されていることが多いため、
    // リアルタイム優先度は設定せず nice 値のみでビッグコア割り当てを促進
}

// ─── initContext ────────────────────────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_gijimemo_whisper_WhisperJni_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str, jboolean use_gpu) {
    (void)thiz;

    const char *model_path = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    LOGI("Loading model from: %s (use_gpu=%d)", model_path, (int)use_gpu);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = (bool)use_gpu;

    struct whisper_context *ctx = whisper_init_from_file_with_params(model_path, cparams);
    if (!ctx) {
        LOGE("Failed to load model: %s (use_gpu=%d)", model_path, (int)use_gpu);
        if (use_gpu) {
            LOGW("GPU init failed, falling back to CPU");
            cparams.use_gpu = false;
            ctx = whisper_init_from_file_with_params(model_path, cparams);
            if (ctx) {
                LOGI("CPU fallback succeeded");
            } else {
                LOGE("CPU fallback also failed: %s", model_path);
            }
        }
    }

    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path);
    return (jlong)ctx;
}

// ─── freeContext ───────────────────────────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_gijimemo_whisper_WhisperJni_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    (void)env;
    (void)thiz;

    struct whisper_context *ctx = (struct whisper_context *)context_ptr;
    if (ctx) {
        whisper_free(ctx);
        LOGI("Model freed");
    }
}

// ─── fullTranscribe ────────────────────────────────────────────────────────────────────────────
// v0.7.4: vad_model_path パラメータ追加 + スレッド優先度設定

JNIEXPORT void JNICALL
Java_com_gijimemo_whisper_WhisperJni_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jstring language_str, jfloatArray audio_data,
        jstring vad_model_path_str) {
    (void)thiz;

    struct whisper_context *ctx = (struct whisper_context *)context_ptr;
    jfloat *audio_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    jsize audio_len = (*env)->GetArrayLength(env, audio_data);

    const char *language = NULL;
    if (language_str != NULL) {
        language = (*env)->GetStringUTFChars(env, language_str, NULL);
    }
    const char *vad_model_path = NULL;
    if (vad_model_path_str != NULL) {
        vad_model_path = (*env)->GetStringUTFChars(env, vad_model_path_str, NULL);
    }
    LOGI("fullTranscribe: language=%s threads=%d samples=%d vad=%s",
         language ? language : "auto", (int)num_threads, (int)audio_len,
         vad_model_path ? vad_model_path : "off");

    // v0.7.4: スレッド優先度設定 → ビッグコア割り当て促進
    set_high_priority();

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = false;
    params.language         = language;
    params.n_threads        = num_threads;
    params.offset_ms        = 0;
    params.no_context       = true;
    params.single_segment   = false;
    // v0.7.4: VAD 有効化（vad_model_path が NULL でなければ）
    params.vad              = (vad_model_path != NULL);
    params.vad_model_path   = vad_model_path;

    whisper_reset_timings(ctx);

    int ret = whisper_full(ctx, params, audio_arr, audio_len);
    if (ret != 0) {
        LOGE("whisper_full failed: %d", ret);
    }

    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_arr, JNI_ABORT);
    if (language != NULL) {
        (*env)->ReleaseStringUTFChars(env, language_str, language);
    }
    if (vad_model_path != NULL) {
        (*env)->ReleaseStringUTFChars(env, vad_model_path_str, vad_model_path);
    }
}

// ─── fullTranscribeChunked ──────────────────────────────────────────────────────────────────
// v0.7.4: vad_model_path パラメータ追加 + スレッド優先度設定

JNIEXPORT void JNICALL
Java_com_gijimemo_whisper_WhisperJni_fullTranscribeChunked(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jstring language_str, jfloatArray audio_data,
        jint offset_ms, jint duration_ms,
        jstring vad_model_path_str) {
    (void)thiz;

    struct whisper_context *ctx = (struct whisper_context *)context_ptr;
    jfloat *audio_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    jsize audio_len = (*env)->GetArrayLength(env, audio_data);

    const char *language = NULL;
    if (language_str != NULL) {
        language = (*env)->GetStringUTFChars(env, language_str, NULL);
    }
    const char *vad_model_path = NULL;
    if (vad_model_path_str != NULL) {
        vad_model_path = (*env)->GetStringUTFChars(env, vad_model_path_str, NULL);
    }
    LOGI("fullTranscribeChunked: language=%s threads=%d samples=%d offset=%dms duration=%dms vad=%s",
         language ? language : "auto", (int)num_threads, (int)audio_len,
         (int)offset_ms, (int)duration_ms,
         vad_model_path ? vad_model_path : "off");

    set_high_priority();

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = false;
    params.language         = language;
    params.n_threads        = num_threads;
    params.offset_ms        = offset_ms;
    params.duration_ms      = duration_ms;
    params.no_context       = true;
    params.single_segment   = false;
    params.vad              = (vad_model_path != NULL);
    params.vad_model_path   = vad_model_path;

    whisper_reset_timings(ctx);

    int ret = whisper_full(ctx, params, audio_arr, audio_len);
    if (ret != 0) {
        LOGE("whisper_full (chunked) failed: %d", ret);
    }

    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_arr, JNI_ABORT);
    if (language != NULL) {
        (*env)->ReleaseStringUTFChars(env, language_str, language);
    }
    if (vad_model_path != NULL) {
        (*env)->ReleaseStringUTFChars(env, vad_model_path_str, vad_model_path);
    }
}

// ─── getTextSegmentCount ───────────────────────────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_gijimemo_whisper_WhisperJni_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    (void)env;
    (void)thiz;

    struct whisper_context *ctx = (struct whisper_context *)context_ptr;
    return (jint)whisper_full_n_segments(ctx);
}

// ─── getTextSegment ────────────────────────────────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_gijimemo_whisper_WhisperJni_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    (void)thiz;

    struct whisper_context *ctx = (struct whisper_context *)context_ptr;
    const char *text = whisper_full_get_segment_text(ctx, (int)index);
    return (*env)->NewStringUTF(env, text);
}

// ─── getSegmentTimestamp0/1 ──────────────────────────────────────────────────────────────────
// v0.7.x Phase 2: per-segment の真の timestamp (ms) を返す。
// whisper_full_get_segment_t0/t1 は centiseconds (10ms 単位) を返すため、×10 して ms に変換する。

JNIEXPORT jlong JNICALL
Java_com_gijimemo_whisper_WhisperJni_getSegmentTimestamp0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint segment_index) {
    (void)env;
    (void)thiz;

    struct whisper_context *ctx = (struct whisper_context *)context_ptr;
    if (ctx == NULL) return 0;
    if (segment_index < 0 || segment_index >= whisper_full_n_segments(ctx)) return 0;
    return (jlong)(whisper_full_get_segment_t0(ctx, (int)segment_index) * 10);
}

JNIEXPORT jlong JNICALL
Java_com_gijimemo_whisper_WhisperJni_getSegmentTimestamp1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint segment_index) {
    (void)env;
    (void)thiz;

    struct whisper_context *ctx = (struct whisper_context *)context_ptr;
    if (ctx == NULL) return 0;
    if (segment_index < 0 || segment_index >= whisper_full_n_segments(ctx)) return 0;
    return (jlong)(whisper_full_get_segment_t1(ctx, (int)segment_index) * 10);
}
