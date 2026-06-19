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
#include "whisper.h"
#include "ggml.h"

#define TAG "WhisperJNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─── initContext ────────────────────────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_gijimemo_whisper_WhisperJni_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    (void)thiz;

    const char *model_path = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    LOGI("Loading model from: %s", model_path);

    struct whisper_context *ctx = whisper_init_from_file(model_path);
    if (!ctx) {
        LOGE("Failed to load model: %s", model_path);
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
// Takes PCM float data (16kHz mono, normalized -1.0..1.0) and runs transcription.

JNIEXPORT void JNICALL
Java_com_gijimemo_whisper_WhisperJni_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jstring language_str, jfloatArray audio_data) {
    (void)thiz;

    struct whisper_context *ctx = (struct whisper_context *)context_ptr;
    jfloat *audio_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    jsize audio_len = (*env)->GetArrayLength(env, audio_data);

    // language_str may be null (= auto detect) or "ja" / "zh" / "en" etc.
    const char *language = NULL;
    if (language_str != NULL) {
        language = (*env)->GetStringUTFChars(env, language_str, NULL);
    }
    LOGI("fullTranscribe: language=%s threads=%d samples=%d",
         language ? language : "auto", (int)num_threads, (int)audio_len);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = false;
    // NULL ならデフォルト ("auto") として whisper が言語検出する。
    params.language         = language;
    params.n_threads        = num_threads;
    params.offset_ms        = 0;
    params.no_context       = true;
    params.single_segment   = false;

    whisper_reset_timings(ctx);

    int ret = whisper_full(ctx, params, audio_arr, audio_len);
    if (ret != 0) {
        LOGE("whisper_full failed: %d", ret);
    }

    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_arr, JNI_ABORT);
    if (language != NULL) {
        (*env)->ReleaseStringUTFChars(env, language_str, language);
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
